package org.snomed.snowstorm.core.rf2.rf2import;

import co.elastic.clients.json.JsonData;
import com.google.common.collect.Sets;
import io.kaicode.elasticvc.api.BranchCriteria;
import io.kaicode.elasticvc.api.BranchService;
import io.kaicode.elasticvc.api.VersionControlHelper;
import io.kaicode.elasticvc.domain.Commit;
import io.kaicode.elasticvc.domain.Entity;
import org.ihtsdo.otf.snomedboot.factory.ImpotentComponentFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.snowstorm.core.data.domain.*;
import org.snomed.snowstorm.core.data.services.BranchMetadataHelper;
import org.snomed.snowstorm.core.data.services.ConceptUpdateHelper;
import org.snomed.snowstorm.core.data.services.IdentifierComponentService;
import org.snomed.snowstorm.core.data.services.ReferenceSetMemberService;
import org.snomed.snowstorm.core.rf2.RF2Constants;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHitsIterator;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.kaicode.elasticvc.api.ComponentService.LARGE_PAGE;
import static java.lang.Long.parseLong;
import static co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.*;
import static io.kaicode.elasticvc.helper.QueryHelper.*;

public class ImportComponentFactoryImpl extends ImpotentComponentFactory {

	private static final int FLUSH_INTERVAL = 5000;

	private final BranchService branchService;
	private final BranchMetadataHelper branchMetadataHelper;

	private final VersionControlHelper versionControlHelper;
	private final String path;
	private Commit commit;
	private BranchCriteria branchCriteriaBeforeOpenCommit;

	private final PersistBuffer<Concept> conceptPersistBuffer;
	private final PersistBuffer<Description> descriptionPersistBuffer;
	private final PersistBuffer<Relationship> relationshipPersistBuffer;
	private final PersistBuffer<Identifier> identifierPersistBuffer;
	private final PersistBuffer<ReferenceSetMember> memberPersistBuffer;
	private final List<PersistBuffer<?>> persistBuffers;
	private final List<PersistBuffer<?>> coreComponentPersistBuffers;
	private final MaxEffectiveTimeCollector maxEffectiveTimeCollector;
	final Map<String, AtomicLong> componentTypeSkippedMap = new HashMap<>();
	private static final Logger logger = LoggerFactory.getLogger(ImportComponentFactoryImpl.class);

	// A small number of stated relationships also appear in the inferred file. These should not be persisted when importing a snapshot.
	Set<Long> statedRelationshipsToSkip = Sets.newHashSet(3187444026L, 3192499027L, 3574321020L);
	boolean coreComponentsFlushed;
	private boolean useModuleEffectiveTimeFilter;


	ImportComponentFactoryImpl(ConceptUpdateHelper conceptUpdateHelper, ReferenceSetMemberService memberService, IdentifierComponentService identifierComponentService, BranchService branchService,
							   BranchMetadataHelper branchMetadataHelper, String path, Integer patchReleaseVersion, boolean copyReleaseFields, boolean clearEffectiveTimes) {

		this.branchService = branchService;
		this.branchMetadataHelper = branchMetadataHelper;
		this.path = path;
		persistBuffers = new ArrayList<>();
		maxEffectiveTimeCollector = new MaxEffectiveTimeCollector();
		coreComponentPersistBuffers = new ArrayList<>();
		ElasticsearchOperations elasticsearchOperations = conceptUpdateHelper.getElasticsearchOperations();
		versionControlHelper = conceptUpdateHelper.getVersionControlHelper();

		conceptPersistBuffer = new PersistBuffer<>() {
			@Override
			public void persistCollection(Collection<Concept> entities) {
				processEntities(entities, patchReleaseVersion, elasticsearchOperations, Concept.class, copyReleaseFields, clearEffectiveTimes);
				if (!entities.isEmpty()) {
					conceptUpdateHelper.doSaveBatchConcepts(entities, commit);
				}
			}
		};
		coreComponentPersistBuffers.add(conceptPersistBuffer);

		descriptionPersistBuffer = new PersistBuffer<>() {
			@Override
			public void persistCollection(Collection<Description> entities) {
				processEntities(entities, patchReleaseVersion, elasticsearchOperations, Description.class, copyReleaseFields, clearEffectiveTimes);
				if (!entities.isEmpty()) {
					conceptUpdateHelper.doSaveBatchDescriptions(entities, commit);
				}
			}
		};
		coreComponentPersistBuffers.add(descriptionPersistBuffer);

		relationshipPersistBuffer = new PersistBuffer<>() {
			@Override
			public void persistCollection(Collection<Relationship> entities) {
				processEntities(entities, patchReleaseVersion, elasticsearchOperations, Relationship.class, copyReleaseFields, clearEffectiveTimes);
				if (!entities.isEmpty()) {
					conceptUpdateHelper.doSaveBatchRelationships(entities, commit);
				}
			}
		};
		coreComponentPersistBuffers.add(relationshipPersistBuffer);

		memberPersistBuffer = new PersistBuffer<>() {
			@Override
			public void persistCollection(Collection<ReferenceSetMember> entities) {
				if (!coreComponentsFlushed) { // Avoid having to sync to check this
					synchronized (this) {
						if (!coreComponentsFlushed) {
							coreComponentPersistBuffers.forEach(PersistBuffer::flush);
							coreComponentsFlushed = true;
						}
					}
				}
				processEntities(entities, patchReleaseVersion, elasticsearchOperations, ReferenceSetMember.class, copyReleaseFields, clearEffectiveTimes);
				if (!entities.isEmpty()) {
					memberService.doSaveBatchMembers(entities, commit);
				}
			}
		};

		identifierPersistBuffer = new PersistBuffer<>() {
			@Override
			public void persistCollection(Collection<Identifier> entities) {
				processEntities(entities, patchReleaseVersion, elasticsearchOperations, Identifier.class, copyReleaseFields, clearEffectiveTimes);
				if (!entities.isEmpty()) {
					identifierComponentService.doSaveBatchIdentifiers(entities, commit);
				}
			}
		};
	}

	/*
		- Mark as changed for version control.
		- Remove if earlier or equal effectiveTime to existing.
		- Copy release fields from existing.
	 */
	private <T extends SnomedComponent<T>> void processEntities(Collection<T> components, Integer patchReleaseVersion, ElasticsearchOperations elasticsearchOperations,
			Class<T> componentClass, boolean copyReleaseFields, boolean clearEffectiveTimes) {

		Map<Integer, List<T>> effectiveDateMap = new HashMap<>();
		components.forEach(component -> {
			component.setChanged(true);
			if (clearEffectiveTimes) {
				component.setEffectiveTimeI(null);
				component.setReleased(false);
				component.setReleaseHash(null);
				component.setReleasedEffectiveTime(null);
			}
			Integer effectiveTimeI = component.getEffectiveTimeI();
			if (effectiveTimeI != null) {
				effectiveDateMap.computeIfAbsent(effectiveTimeI, i -> new ArrayList<>()).add(component);
				maxEffectiveTimeCollector.add(effectiveTimeI);
			}
		});
		// patchReleaseVersion=-1 is a special case which allows replacing any effectiveTime
		if (!useModuleEffectiveTimeFilter && (patchReleaseVersion == null || !patchReleaseVersion.equals(-1))) {
			performPatch(components, patchReleaseVersion, elasticsearchOperations, componentClass, effectiveDateMap);
		}
		if (copyReleaseFields) {
			Map<String, T> idToUnreleasedComponentMap = components.stream().filter(component -> component.getEffectiveTime() == null).collect(Collectors.toMap(T::getId, Function.identity()));
			if (!idToUnreleasedComponentMap.isEmpty()) {
				String idField = idToUnreleasedComponentMap.values().iterator().next().getIdField();
				try (SearchHitsIterator<T> stream = elasticsearchOperations.searchForStream(new NativeQueryBuilder()
						.withQuery(bool(b -> b
								.must(branchCriteriaBeforeOpenCommit.getEntityBranchCriteria(componentClass))
								.must(termQuery(SnomedComponent.Fields.RELEASED, true))
								.filter(termsQuery(idField, idToUnreleasedComponentMap.keySet())))
						)
						.withPageable(LARGE_PAGE)
						.build(), componentClass)) {
					stream.forEachRemaining(hit -> {
						T t = idToUnreleasedComponentMap.get(hit.getContent().getId());
						t.copyReleaseDetails(hit.getContent());
						t.updateEffectiveTime();
					});
				}
			}
		}
	}

	private <T extends SnomedComponent<T>> void performPatch(Collection<T> components, Integer patchReleaseVersion, ElasticsearchOperations elasticsearchOperations, Class<T> componentClass, Map<Integer, List<T>> effectiveDateMap) {
		for (Integer effectiveTime : new TreeSet<>(effectiveDateMap.keySet())) {
			// Find component states with an equal or greater effective time
			boolean replacementOfThisEffectiveTimeAllowed = patchReleaseVersion != null && patchReleaseVersion.equals(effectiveTime);
			List<T> componentsAtDate = effectiveDateMap.get(effectiveTime);
			String idField = componentsAtDate.get(0).getIdField();
			AtomicInteger alreadyExistingComponentCount = new AtomicInteger();
			try (SearchHitsIterator<T> componentsWithSameOrLaterEffectiveTime = elasticsearchOperations.searchForStream(new NativeQueryBuilder()
					.withQuery(bool(b -> b
							.must(branchCriteriaBeforeOpenCommit.getEntityBranchCriteria(componentClass))
							.must(termsQuery(idField, componentsAtDate.stream().map(T::getId).toList()))
							.must(replacementOfThisEffectiveTimeAllowed ?
									range().field(SnomedComponent.Fields.EFFECTIVE_TIME).gt(JsonData.of(effectiveTime)).build()._toQuery()
									: range().field(SnomedComponent.Fields.EFFECTIVE_TIME).gte(JsonData.of(effectiveTime)).build()._toQuery())))
					.withSourceFilter(new FetchSourceFilter(new String[]{idField}, null))// Only fetch the id
					.withPageable(LARGE_PAGE)
					.build(), componentClass)) {
				componentsWithSameOrLaterEffectiveTime.forEachRemaining(hit -> {
					// Skip component import
					components.remove(hit.getContent());// Compared by id only
					alreadyExistingComponentCount.incrementAndGet();
				});
			}
			componentTypeSkippedMap.computeIfAbsent(componentClass.getSimpleName(), key -> new AtomicLong()).addAndGet(alreadyExistingComponentCount.get());
		}
	}

	@Override
	public void loadingComponentsStarting() {
		setCommit(branchService.openCommit(path, branchMetadataHelper.getBranchLockMetadata("Loading components from RF2 import.")));
	}

	protected void setCommit(Commit commit) {
		this.commit = commit;
		branchCriteriaBeforeOpenCommit = versionControlHelper.getBranchCriteriaBeforeOpenCommit(commit);
	}

	@Override
	public void loadingComponentsCompleted() {
		completeImportCommit();
	}

	void completeImportCommit() {
		if (!componentTypeSkippedMap.isEmpty()) {
			for (String type : componentTypeSkippedMap.keySet()) {
				logger.info("{} components of type {} were not imported from RF2 because a newer version was found.", componentTypeSkippedMap.get(type).get(), type);
			}
		}
		persistBuffers.forEach(PersistBuffer::flush);
		commit.markSuccessful();
		commit.close();
		commit = null;
	}

	@Override
	public void newConceptState(String conceptId, String effectiveTime, String active, String moduleId, String definitionStatusId) {
		Integer effectiveTimeI = getEffectiveTimeI(effectiveTime);
		final Concept concept = new Concept(conceptId, effectiveTimeI, isActive(active), moduleId, definitionStatusId);
		if (effectiveTimeI != null) {
			concept.release(effectiveTimeI);
		}
		conceptPersistBuffer.save(concept);
	}

	@Override
	public void newRelationshipState(String id, String effectiveTime, String active, String moduleId, String sourceId, String destinationId,
			String relationshipGroup, String typeId, String characteristicTypeId, String modifierId) {
		Integer effectiveTimeI = getEffectiveTimeI(effectiveTime);
		final Relationship relationship = new Relationship(id, effectiveTimeI, isActive(active), moduleId, sourceId,
				destinationId, Integer.parseInt(relationshipGroup), typeId, characteristicTypeId, modifierId);
		if (effectiveTimeI != null) {
			relationship.release(effectiveTimeI);
		}

		if (statedRelationshipsToSkip != null
				&& relationship.getCharacteristicTypeId().equals(Concepts.STATED_RELATIONSHIP)
				&& statedRelationshipsToSkip.contains(parseLong(relationship.getId()))) {
			// Do not persist relationship
			return;
		}

		relationshipPersistBuffer.save(relationship);
	}

	@Override
	public void newConcreteRelationshipState(String id, String effectiveTime, String active, String moduleId, String sourceId, String value,
											 String relationshipGroup, String typeId, String characteristicTypeId, String modifierId) {
		Integer effectiveTimeI = getEffectiveTimeI(effectiveTime);
		final Relationship relationship = new Relationship(id, effectiveTimeI, isActive(active), moduleId, sourceId,
				value, Integer.parseInt(relationshipGroup), typeId, characteristicTypeId, modifierId);
		if (effectiveTimeI != null) {
			relationship.release(effectiveTimeI);
		}

		relationshipPersistBuffer.save(relationship);
	}

	@Override
	public void newDescriptionState(String id, String effectiveTime, String active, String moduleId, String conceptId, String languageCode,
			String typeId, String term, String caseSignificanceId) {

		Integer effectiveTimeI = getEffectiveTimeI(effectiveTime);
		final Description description = new Description(id, effectiveTimeI, isActive(active), moduleId, conceptId, languageCode, typeId, term, caseSignificanceId);
		if (effectiveTimeI != null) {
			description.release(effectiveTimeI);
		}
		descriptionPersistBuffer.save(description);
	}

	@Override
	public void newIdentifierState(String alternateIdentifier, String effectiveTime, String active, String moduleId, String identifierSchemeId, String referencedComponentId) {
		Integer effectiveTimeI = getEffectiveTimeI(effectiveTime);
		Identifier identifier = new Identifier(alternateIdentifier, effectiveTimeI, isActive(active), moduleId, identifierSchemeId, referencedComponentId);
		if (effectiveTimeI != null) {
			identifier.release(effectiveTimeI);
		}
		identifierPersistBuffer.save(identifier);
	}

	@Override
	public void newReferenceSetMemberState(String[] fieldNames, String id, String effectiveTime, String active, String moduleId, String refsetId,
			String referencedComponentId, String... otherValues) {

		Integer effectiveTimeI = getEffectiveTimeI(effectiveTime);
		ReferenceSetMember member = new ReferenceSetMember(id, effectiveTimeI, isActive(active), moduleId, refsetId, referencedComponentId);
		for (int i = RF2Constants.MEMBER_ADDITIONAL_FIELD_OFFSET; i < fieldNames.length; i++) {
			if (i - RF2Constants.MEMBER_ADDITIONAL_FIELD_OFFSET < otherValues.length) {
				member.setAdditionalField(fieldNames[i], otherValues[i - RF2Constants.MEMBER_ADDITIONAL_FIELD_OFFSET]);
			} else {
				member.setAdditionalField(fieldNames[i], "");
			}
		}
		if (effectiveTimeI != null) {
			member.release(effectiveTimeI);
		}
		memberPersistBuffer.save(member);
	}

	Integer getEffectiveTimeI(String effectiveTime) {
		return effectiveTime != null && !effectiveTime.isEmpty() && RF2Constants.EFFECTIVE_DATE_PATTERN.matcher(effectiveTime).matches() ? Integer.parseInt(effectiveTime) : null;
	}

	Integer getMaxEffectiveTime() {
		return maxEffectiveTimeCollector.getMaxEffectiveTime();
	}

	protected BranchService getBranchService() {
		return branchService;
	}

	private boolean isActive(String active) {
		return "1".equals(active);
	}

	public Commit getCommit() {
		return commit;
	}

	public void useModuleEffectiveTimeFilter(boolean useModuleEffectiveTimeFilter) {
		this.useModuleEffectiveTimeFilter = useModuleEffectiveTimeFilter;
	}

	private abstract class PersistBuffer<E extends Entity> {

		private final List<E> entities = new ArrayList<>();

		PersistBuffer() {
			persistBuffers.add(this);
		}

		synchronized void save(E entity) {
			entities.add(entity);
			if (entities.size() >= FLUSH_INTERVAL) {
				flush();
			}
		}

		synchronized void flush() {
			persistCollection(entities);
			entities.clear();
		}

		abstract void persistCollection(Collection<E> entities);

	}

}
