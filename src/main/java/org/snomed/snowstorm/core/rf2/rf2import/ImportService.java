package org.snomed.snowstorm.core.rf2.rf2import;

import io.kaicode.elasticvc.api.BranchService;
import io.kaicode.elasticvc.domain.Branch;
import io.kaicode.elasticvc.domain.Commit;
import io.kaicode.elasticvc.domain.Metadata;
import org.ihtsdo.otf.snomedboot.ReleaseImportException;
import org.ihtsdo.otf.snomedboot.ReleaseImporter;
import org.ihtsdo.otf.snomedboot.factory.LoadingProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.snowstorm.core.data.domain.CodeSystem;
import org.snomed.snowstorm.core.data.domain.Concept;
import org.snomed.snowstorm.core.data.services.*;
import org.snomed.snowstorm.core.rf2.RF2Type;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;

import static org.snomed.snowstorm.core.data.services.BranchMetadataHelper.*;
import static org.snomed.snowstorm.core.data.services.BranchMetadataKeys.IMPORT_TYPE;
import static org.snomed.snowstorm.core.rf2.RF2Type.FULL;

@Service
public class ImportService {

	public static final String BATCH_CHANGE_KEY = "batch-change";

	private final Map<String, ImportJob> importJobMap;

	private static final LoadingProfile DEFAULT_LOADING_PROFILE = LoadingProfile.complete;

	@Autowired
	private ConceptUpdateHelper conceptUpdateHelper;

	@Autowired
	private ReferenceSetMemberService memberService;

	@Autowired
	private IdentifierComponentService identifierComponentService;

	@Autowired
	private BranchService branchService;

	@Autowired
	private BranchMetadataHelper branchMetadataHelper;

	@Autowired
	private ExecutorService executorService;

	@Autowired
	private CodeSystemService codeSystemService;

	@Autowired
	private ConceptService conceptService;

	@Autowired
	private MostRecentEffectiveTimeFinder mostRecentEffectiveTimeFinder;

	private final Logger logger = LoggerFactory.getLogger(getClass());

	public ImportService() {
		importJobMap = new HashMap<>();
	}

	public String createJob(RF2Type importType, String branchPath, boolean createCodeSystemVersion, boolean clearEffectiveTimes) {
		return createJob(new RF2ImportConfiguration(importType, branchPath)
				.setCreateCodeSystemVersion(createCodeSystemVersion)
				.setClearEffectiveTimes(clearEffectiveTimes));
	}

	public String createJob(RF2ImportConfiguration importConfiguration) {
		String id = UUID.randomUUID().toString();

		// Validate branch
		String branchPath = importConfiguration.getBranchPath();
		if (!branchService.exists(branchPath)) {
			throw new IllegalArgumentException(String.format("Branch %s does not exist.", branchPath));
		}

		if (importConfiguration.getType() == FULL) {
			Branch latest = branchService.findLatest(branchPath);
			if (!branchPath.equals("MAIN") || hasExistingContent(latest)) {
				throw new IllegalArgumentException("FULL import is only implemented for the MAIN branch and when there is no existing content.");
			}
		}

		if (importConfiguration.isCreateCodeSystemVersion()) {
			// Check there is a code system on this branch
			Optional<CodeSystem> optionalCodeSystem = codeSystemService.findAll().stream().filter(codeSystem -> codeSystem.getBranchPath().equals(branchPath)).findAny();
			if (optionalCodeSystem.isEmpty()) {
				throw new IllegalArgumentException(String.format("The %s option has been used but there is no codesystem on branchPath %s.", "createCodeSystemVersion", branchPath));
			}
		}

		importJobMap.put(id, new ImportJob(importConfiguration));
		return id;
	}

	private boolean hasExistingContent(Branch branch) {
		// check whether there are existing concepts on the branch by fetching the first page
		Page<Concept> results = conceptService.findAll(branch.getPath(), PageRequest.of(0,1));
		return results.getTotalElements() > 0;
	}

	public void importArchive(String importId, InputStream releaseFileStream) throws ReleaseImportException {
		ImportJob job = getJob(importId);
		if (job.getStatus() != ImportJob.ImportStatus.WAITING_FOR_FILE) {
			throw new IllegalStateException("Import Job must be in state " + ImportJob.ImportStatus.WAITING_FOR_FILE);
		}
		RF2Type importType = job.getType();
		String branchPath = job.getBranchPath();
		Integer patchReleaseVersion = job.getPatchReleaseVersion();
		setImportMetadata(importType, branchPath, job.isCreateCodeSystemVersion());
		try {
			Date start = new Date();
			logger.info("Starting RF2 {}{} import on branch {}. ID {}", importType, patchReleaseVersion != null ? " RELEASE PATCH on effectiveTime " + patchReleaseVersion : "", branchPath, importId);

			job.setStatus(ImportJob.ImportStatus.RUNNING);
			LoadingProfile loadingProfile = DEFAULT_LOADING_PROFILE
					.withModuleIds(job.getModuleIds().toArray(new String[]{}));

			final Integer maxEffectiveTime = importFiles(releaseFileStream, job, importType, branchPath, patchReleaseVersion, new ReleaseImporter(), loadingProfile);

			if (job.isCreateCodeSystemVersion() && importType != FULL && maxEffectiveTime != null) {
				// Create Code System version if a code system exists on this path
				codeSystemService.createVersionIfCodeSystemFoundOnPath(branchPath, maxEffectiveTime, job.isInternalRelease());
			}

			job.setStatus(ImportJob.ImportStatus.COMPLETED);
			long seconds = (new Date().getTime() - start.getTime()) / 1_000;
			logger.info("Completed RF2 {} import on branch {} in {} seconds. ID {}", importType, branchPath, seconds, importId);
		} catch (Exception e) {
			logger.error("Failed RF2 {} import on branch {}. ID {}", importType, branchPath, importId, e);
			job.setStatus(ImportJob.ImportStatus.FAILED);
			throw e;
		} finally {
			clearImportMetadata(branchPath);
		}
	}

	/**
	 * Imports the uploaded files, depending on the {@link RF2Type} <code>importType</code>.
	 * If an <code>importType</code> is passed in which is not associated to an {@link RF2Type}, then
	 * an {@code IllegalStateException} will be thrown.
	 *
	 * @param releaseFileStream   The release ZIP files.
	 * @param job                 Used to determine whether <code>org.snomed.snowstorm.core.rf2.rf2import.ImportJob#isCreateCodeSystemVersion() == false</code>
	 *                            and if <code>org.snomed.snowstorm.core.rf2.rf2import.ImportJob#isClearEffectiveTimes() == true</code> when
	 *                            doing a delta import.
	 * @param importType          The type of import which is being undertaken.
	 * @param branchPath          Path to the {@link Branch}.
	 * @param patchReleaseVersion The version of the patch release.
	 * @param releaseImporter     Used to import the files.
	 * @param loadingProfile      Used when finding the release files to import.
	 * @return The max effective time which specifies the date that a specific version of a component was
	 * released. A component may be versioned over time, but only one version of each component can be valid
	 * at a specific point in time.
	 * @throws ReleaseImportException If an error occurs while trying to import the release files.
	 * @see RF2Type#DELTA
	 * @see RF2Type#SNAPSHOT
	 * @see RF2Type#FULL
	 */
	private Integer importFiles(final InputStream releaseFileStream, final ImportJob job, final RF2Type importType, final String branchPath, final Integer patchReleaseVersion,
			final ReleaseImporter releaseImporter, final LoadingProfile loadingProfile) throws ReleaseImportException {
        return switch (importType) {
            case DELTA ->
                    deltaImport(releaseFileStream, job, branchPath, patchReleaseVersion, releaseImporter, loadingProfile);
            case SNAPSHOT ->
                    snapshotImport(releaseFileStream, job, branchPath, patchReleaseVersion, releaseImporter, loadingProfile);
            case FULL -> fullImport(releaseFileStream, branchPath, releaseImporter, loadingProfile);
            default -> throw new IllegalStateException("Unexpected import type: " + importType);
        };
	}

	private void setImportMetadata(RF2Type importType, String branchPath, boolean createCodeSystemVersion) {
		Branch branch = branchService.findLatest(branchPath);
		Metadata metadata = branch.getMetadata();
		final Map<String, String> internalMetadataMap = metadata.getMapOrCreate(INTERNAL_METADATA_KEY);
		internalMetadataMap.put(IMPORT_TYPE, importType.getName());
		if (importType == FULL || createCodeSystemVersion) {
			internalMetadataMap.put(IMPORTING_CODE_SYSTEM_VERSION, "true");
		}

		boolean codeSystem = codeSystemService.findByBranchPath(branchPath).isPresent();
		if (!codeSystem) {
			metadata.getMapOrCreate(AUTHOR_FLAGS_METADATA_KEY).put(BATCH_CHANGE_KEY, "true");
		}
		// Import metadata is saved to the store rather than just existing within the Commit object.
		// We can also not use the transient metadata function.
		// This is because imports span multiple commits when importing a FULL type or creating a code system version.
		branchService.updateMetadata(branchPath, metadata);
	}

	private void clearImportMetadata(String branchPath) {
		Metadata metadata = branchService.findLatest(branchPath).getMetadata();
		final Map<String, String> internalMetadataMap = metadata.getMapOrCreate(INTERNAL_METADATA_KEY);
		internalMetadataMap.remove(IMPORT_TYPE);
		internalMetadataMap.remove(IMPORTING_CODE_SYSTEM_VERSION);
		branchService.updateMetadata(branchPath, metadata);
	}

	private Integer fullImport(final InputStream releaseFileStream, final String branchPath, final ReleaseImporter releaseImporter,
			final LoadingProfile loadingProfile) throws ReleaseImportException {

		final FullImportComponentFactoryImpl importComponentFactory = getFullImportComponentFactory(branchPath);
		try {
			releaseImporter.loadFullReleaseFiles(releaseFileStream, loadingProfile, importComponentFactory, true);
			return null;
		} catch (ReleaseImportException e) {
			rollbackIncompleteCommit(importComponentFactory);
			throw e;
		}
	}

	private Integer snapshotImport(final InputStream releaseFileStream, ImportJob job, final String branchPath, final Integer patchReleaseVersion,
			final ReleaseImporter releaseImporter, final LoadingProfile loadingProfile) throws ReleaseImportException {

		// If we are not creating a new version copy the release fields from the existing components
		final ImportComponentFactoryImpl importComponentFactory = getImportComponentFactory(branchPath, patchReleaseVersion, !job.isCreateCodeSystemVersion(), job.isClearEffectiveTimes());
		importComponentFactory.useModuleEffectiveTimeFilter(true);
		try {
			logger.info("Start fetching latest effectiveTime imported already for each module on path {}", branchPath);
			Map<String, Integer> effectiveTimeByModuleId = mostRecentEffectiveTimeFinder.getEffectiveTimeByModuleId(branchPath, true);
			if (!effectiveTimeByModuleId.isEmpty()) {
				logger.info("Fetched latest effectiveTime by module on path {} {}", branchPath, effectiveTimeByModuleId);
			}
			logger.info("Completed fetching latest effectiveTime for each module on path {}", branchPath);

			releaseImporter.loadSnapshotReleaseFiles(releaseFileStream, loadingProfile.withModuleEffectiveTimeFilter(effectiveTimeByModuleId), importComponentFactory, true);
			return importComponentFactory.getMaxEffectiveTime();
		} catch (ReleaseImportException e) {
			rollbackIncompleteCommit(importComponentFactory);
			throw e;
		}
	}

	private Integer deltaImport(final InputStream releaseFileStream, final ImportJob job, final String branchPath, final Integer patchReleaseVersion,
			final ReleaseImporter releaseImporter, final LoadingProfile loadingProfile) throws ReleaseImportException {

		// If we are not creating a new version copy the release fields from the existing components
		final ImportComponentFactoryImpl importComponentFactory =
				getImportComponentFactory(branchPath, patchReleaseVersion, !job.isCreateCodeSystemVersion(), job.isClearEffectiveTimes());
		try {
			releaseImporter.loadDeltaReleaseFiles(releaseFileStream, loadingProfile, importComponentFactory, true);
			return importComponentFactory.getMaxEffectiveTime();
		} catch (ReleaseImportException e) {
			rollbackIncompleteCommit(importComponentFactory);
			throw e;
		}
	}

	private void rollbackIncompleteCommit(ImportComponentFactoryImpl importComponentFactory) {
		final Commit commit = importComponentFactory.getCommit();
		if (commit != null) {
			logger.info("Triggering rollback of failed import commit on {} at {}", commit.getBranch().getPath(), commit.getTimepoint().getTime());
			// Closing the commit without marking as successful causes commit rollback.
			commit.close();
		}
	}

	private ImportComponentFactoryImpl getImportComponentFactory(String branchPath, Integer patchReleaseVersion, boolean copyReleaseFields, boolean clearEffectiveTimes) {
		return new ImportComponentFactoryImpl(conceptUpdateHelper, memberService, identifierComponentService, branchService, branchMetadataHelper,
				branchPath, patchReleaseVersion, copyReleaseFields, clearEffectiveTimes);
	}

	private FullImportComponentFactoryImpl getFullImportComponentFactory(String branchPath) {
		return new FullImportComponentFactoryImpl(conceptUpdateHelper, memberService, identifierComponentService, branchService, branchMetadataHelper, codeSystemService,
				branchPath, null);
	}

	@PreAuthorize("hasPermission('AUTHOR', #branchPath)")
	public void importArchiveAsync(String importId, @SuppressWarnings("unused") String branchPath, File tempFile, boolean deleteFileAfterImport) {
		final SecurityContext securityContext = SecurityContextHolder.getContext();
		executorService.submit(() -> {
			SecurityContextHolder.setContext(securityContext);
			try (FileInputStream releaseFileStream = new FileInputStream(tempFile)) {
				importArchive(importId, releaseFileStream);
			} catch (ReleaseImportException e) {
				// Swallow exception - already logged and this is an async method
			} catch (IOException e) {
				logger.error("Import failed. Error reading stream for temp file {}", tempFile.getAbsolutePath(), e);
				getJob(importId).setStatus(ImportJob.ImportStatus.FAILED);
			} finally {
			if (deleteFileAfterImport && tempFile != null) {
				if (!tempFile.delete()) {
					logger.warn("Failed to delete temp import file {}", tempFile.getAbsolutePath());
				}
			}
			}
		});
	}

	public ImportJob getImportJobOrThrow(@PathVariable String importId) {
		ImportJob importJob = getJob(importId);
		if (importJob == null) {
			throw new NotFoundException("Import job not found.");
		}
		return importJob;
	}

	private ImportJob getJob(String importId) {
		return importJobMap.get(importId);
	}
}
