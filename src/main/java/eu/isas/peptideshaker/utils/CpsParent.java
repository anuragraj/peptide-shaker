package eu.isas.peptideshaker.utils;

import com.compomics.util.db.ObjectsCache;
import com.compomics.util.experiment.MsExperiment;
import com.compomics.util.experiment.ProteomicAnalysis;
import com.compomics.util.experiment.biology.Sample;
import com.compomics.util.experiment.identification.Advocate;
import com.compomics.util.experiment.identification.Identification;
import com.compomics.util.experiment.identification.IdentificationMethod;
import com.compomics.util.experiment.identification.SearchParameters;
import com.compomics.util.experiment.identification.SequenceFactory;
import com.compomics.util.experiment.massspectrometry.SpectrumFactory;
import com.compomics.util.waiting.WaitingHandler;
import com.compomics.util.preferences.AnnotationPreferences;
import com.compomics.util.preferences.GenePreferences;
import com.compomics.util.preferences.IdFilter;
import com.compomics.util.preferences.PTMScoringPreferences;
import com.compomics.util.preferences.ProcessingPreferences;
import com.compomics.util.preferences.SequenceMatchingPreferences;
import eu.isas.peptideshaker.PeptideShaker;
import eu.isas.peptideshaker.export.CpsExporter;
import eu.isas.peptideshaker.fileimport.CpsFileImporter;
import eu.isas.peptideshaker.myparameters.PeptideShakerSettings;
import eu.isas.peptideshaker.preferences.DisplayPreferences;
import eu.isas.peptideshaker.preferences.FilterPreferences;
import eu.isas.peptideshaker.preferences.ProjectDetails;
import eu.isas.peptideshaker.preferences.SpectrumCountingPreferences;
import eu.isas.peptideshaker.preferences.SpectrumCountingPreferences.SpectralCountingMethod;
import eu.isas.peptideshaker.preferences.UserPreferences;
import eu.isas.peptideshaker.preferences.UserPreferencesParent;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import org.apache.commons.compress.archivers.ArchiveException;

/**
 * Implementing this abstract class allows interacting with a cps files.
 *
 * @author Marc Vaudel
 */
public class CpsParent extends UserPreferencesParent {

    /**
     * The identification.
     */
    protected Identification identification;
    /**
     * The identification features generator.
     */
    protected IdentificationFeaturesGenerator identificationFeaturesGenerator;
    /**
     * The identification filter used.
     */
    protected IdFilter idFilter = new IdFilter();
    /**
     * The annotation preferences to use.
     */
    protected AnnotationPreferences annotationPreferences;
    /**
     * The spectrum counting preferences.
     */
    protected SpectrumCountingPreferences spectrumCountingPreferences;
    /**
     * The PTM scoring preferences.
     */
    protected PTMScoringPreferences ptmScoringPreferences;
    /**
     * The project details.
     */
    protected ProjectDetails projectDetails;
    /**
     * The search parameters.
     */
    protected SearchParameters searchParameters;
    /**
     * The processing preferences.
     */
    protected ProcessingPreferences processingPreferences;
    /**
     * The metrics stored during processing.
     */
    protected Metrics metrics;
    /**
     * The gene preferences.
     */
    protected GenePreferences genePreferences = new GenePreferences();
    /**
     * The MS experiment class.
     */
    protected MsExperiment experiment;
    /**
     * The sample.
     */
    protected Sample sample;
    /**
     * The replicate number.
     */
    protected int replicateNumber;
    /**
     * The proteomic analysis.
     */
    protected ProteomicAnalysis proteomicAnalysis;
    /**
     * The cache used to store objects.
     */
    protected ObjectsCache objectsCache;
    /**
     * The filter preferences.
     */
    protected FilterPreferences filterPreferences = new FilterPreferences();
    /**
     * The display preferences.
     */
    protected DisplayPreferences displayPreferences = new DisplayPreferences();
    /**
     * The sequence matching preferences.
     */
    protected SequenceMatchingPreferences sequenceMatchingPreferences;
    /**
     * The currently loaded cps file.
     */
    protected File cpsFile = null;

    /**
     * Loads the information from a cps file.
     *
     * @param jarFilePath the path to the jar file
     * @param waitingHandler a waiting handler displaying feedback to the user.
     * Ignored if null
     * @throws FileNotFoundException
     * @throws IOException
     * @throws ClassNotFoundException
     * @throws SQLException
     */
    public void loadCpsFile(String jarFilePath, WaitingHandler waitingHandler) throws FileNotFoundException, IOException, ClassNotFoundException, SQLException {

        CpsFileImporter cpsFileImporter = new CpsFileImporter(cpsFile, jarFilePath, waitingHandler);

        // close any open connection to an identification database
        if (identification != null) {
            identification.close();
        }

        // Get the experiment data
        experiment = cpsFileImporter.getExperiment();
        ArrayList<Sample> samples = cpsFileImporter.getSamples();
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("No sample found for the experiment " + experiment.getReference());
        }
        sample = samples.get(0);
        if (samples.size() > 1) { // pretty unlikely to happen for now
            String message = samples.size() + " samples found in experiment " + experiment.getReference() + ", sample " + sample.getReference() + " selected by default.";
            if (waitingHandler != null) {
                waitingHandler.appendReport(message, true, true);
            }
        }
        ArrayList<Integer> replicates = cpsFileImporter.getReplicates(sample);
        if (replicates == null || replicates.isEmpty()) {
            throw new IllegalArgumentException("No replicate found for the sample " + sample.getReference() + " of experiment " + experiment.getReference());
        }
        replicateNumber = replicates.get(0);
        if (replicates.size() > 1) { // pretty unlikely to happen for now
            if (waitingHandler != null) {
                waitingHandler.appendReport(replicates.size() + " replicates found in sample " + sample.getReference()
                        + " of experiment " + experiment.getReference() + ", replicate " + sample.getReference() + " selected by default.", true, true);
            }
        }
        proteomicAnalysis = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber);

        // Get PeptideShaker settings
        PeptideShakerSettings experimentSettings = cpsFileImporter.getExperimentSettings();
        idFilter = experimentSettings.getIdFilter();
        annotationPreferences = experimentSettings.getAnnotationPreferences();
        spectrumCountingPreferences = experimentSettings.getSpectrumCountingPreferences();
        ptmScoringPreferences = experimentSettings.getPTMScoringPreferences();
        projectDetails = experimentSettings.getProjectDetails();
        HashMap<Integer, Advocate> userAdvocateMapping = projectDetails.getUserAdvocateMapping();
        if (userAdvocateMapping != null) {
            Advocate.setUserAdvocates(userAdvocateMapping);
        }
        searchParameters = experimentSettings.getSearchParameters();
        processingPreferences = experimentSettings.getProcessingPreferences();
        metrics = experimentSettings.getMetrics();
        genePreferences = experimentSettings.getGenePreferences();
        filterPreferences = experimentSettings.getFilterPreferences();
        displayPreferences = experimentSettings.getDisplayPreferences();
        sequenceMatchingPreferences = experimentSettings.getSequenceMatchingPreferences();

        // backwards compatability for the gene preferences
        if (genePreferences.getCurrentSpecies() == null) {
            genePreferences = new GenePreferences();
        }
        if (genePreferences.getCurrentSpecies() != null && genePreferences.getCurrentSpeciesType() == null) {
            genePreferences.setCurrentSpeciesType("Vertebrates");
        }

        // backwards compatability for the filter preferences
        if (filterPreferences == null) {
            filterPreferences = new FilterPreferences();
        }

        // backwards compatability for the display preferences
        if (displayPreferences != null) {
            displayPreferences.compatibilityCheck(searchParameters.getModificationProfile());
        } else {
            displayPreferences = new DisplayPreferences();
            displayPreferences.setDefaultSelection(searchParameters.getModificationProfile());
        }

        // backwards compatability for the sequence matching preferences
        if (sequenceMatchingPreferences == null) {
            sequenceMatchingPreferences = SequenceMatchingPreferences.getDefaultSequenceMatching(searchParameters);
        }

        if (waitingHandler != null && waitingHandler.isRunCanceled()) {
            waitingHandler.setRunFinished();
            return;
        }

        // Get identification details and set up caches
        identification = proteomicAnalysis.getIdentification(IdentificationMethod.MS2_IDENTIFICATION);
        if (identification.getSpectrumIdentificationMap() == null) {
            // 0.18 version, needs update of the spectrum mapping
            identification.updateSpectrumMapping();
        }
        identificationFeaturesGenerator = new IdentificationFeaturesGenerator(identification, searchParameters, idFilter, metrics, spectrumCountingPreferences, sequenceMatchingPreferences);
        if (experimentSettings.getIdentificationFeaturesCache() != null) {
            identificationFeaturesGenerator.setIdentificationFeaturesCache(experimentSettings.getIdentificationFeaturesCache());
        }
        objectsCache = new ObjectsCache();
        objectsCache.setAutomatedMemoryManagement(true);
        String dbFolder = PeptideShaker.getSerializationDirectory(jarFilePath).getAbsolutePath();
        identification.establishConnection(dbFolder, false, objectsCache);
        loadUserPreferences();
        userPreferences.addRecentProject(cpsFile);
        saveUserPreferences();
    }

    /**
     * Saves the project in the cps file.
     *
     * @param waitingHandler waiting handler displaying feedback to the user.
     * can be null.
     * @param emptyCache if true the cache will be emptied
     * @throws IOException
     * @throws SQLException
     * @throws FileNotFoundException
     * @throws ArchiveException
     */
    public void saveProject(WaitingHandler waitingHandler, boolean emptyCache) throws IOException, SQLException, FileNotFoundException, ArchiveException {
        CpsExporter.saveAs(cpsFile, waitingHandler, experiment, identification, searchParameters,
                annotationPreferences, spectrumCountingPreferences, projectDetails, metrics,
                processingPreferences, identificationFeaturesGenerator.getIdentificationFeaturesCache(),
                ptmScoringPreferences, genePreferences, objectsCache, emptyCache, idFilter, sequenceMatchingPreferences, PeptideShaker.getJarFilePath());

        loadUserPreferences();
        userPreferences.addRecentProject(cpsFile);
        saveUserPreferences();
    }

    /**
     * Loads the FASTA file in the sequence factory.
     *
     * @param waitingHandler a waiting handler displaying progress to the user.
     * Can be null
     * @throws FileNotFoundException
     * @throws IOException
     * @throws ClassNotFoundException
     *
     * @return a boolean indicating whether the loading was successful
     */
    public boolean loadFastaFile(WaitingHandler waitingHandler) throws FileNotFoundException, IOException, ClassNotFoundException {
        return loadFastaFile(null, waitingHandler);
    }

    /**
     * Loads the FASTA file in the sequence factory.
     *
     * @param folder a folder to look into, the user last selected folder for
     * instance, can be null
     * @param waitingHandler a waiting handler displaying progress to the user.
     * Can be null
     * @throws FileNotFoundException
     * @throws IOException
     * @throws ClassNotFoundException
     * @return a boolean indicating whether the loading was successful
     */
    public boolean loadFastaFile(File folder, WaitingHandler waitingHandler) throws FileNotFoundException, IOException, ClassNotFoundException {

        SequenceFactory sequenceFactory = SequenceFactory.getInstance();

        // Load fasta file
        File providedFastaLocation = searchParameters.getFastaFile();
        String fileName = providedFastaLocation.getName();
        File projectFolder = cpsFile.getParentFile();
        File dataFolder = new File(projectFolder, "data");

        if (providedFastaLocation.exists()) {
            sequenceFactory.loadFastaFile(providedFastaLocation, waitingHandler);
        } else if (folder != null && new File(folder, fileName).exists()) {
            sequenceFactory.loadFastaFile(new File(folder, fileName), waitingHandler);
            searchParameters.setFastaFile(new File(folder, fileName));
        } else if (new File(projectFolder, fileName).exists()) {
            sequenceFactory.loadFastaFile(new File(projectFolder, fileName), waitingHandler);
            searchParameters.setFastaFile(new File(projectFolder, fileName));
        } else if (new File(dataFolder, fileName).exists()) {
            sequenceFactory.loadFastaFile(new File(dataFolder, fileName), waitingHandler);
            searchParameters.setFastaFile(new File(dataFolder, fileName));
        } else {
            return false;
        }

        return true;
    }

    /**
     * Loads the spectra in the spectrum factory.
     *
     * @param waitingHandler a waiting handler displaying progress to the user.
     * Can be null
     * @throws FileNotFoundException
     * @throws IOException
     *
     * @return a boolean indicating whether the loading was successful
     */
    public boolean loadSpectrumFiles(WaitingHandler waitingHandler) throws FileNotFoundException, IOException {
        return loadSpectrumFiles(null, waitingHandler);
    }

    /**
     * Loads the spectra in the spectrum factory.
     *
     * @param folder a folder to look into, the user last selected folder for
     * instance, can be null
     * @param waitingHandler a waiting handler displaying progress to the user.
     * Can be null
     * @throws FileNotFoundException
     * @throws IOException
     *
     * @return a boolean indicating whether the loading was successful
     */
    public boolean loadSpectrumFiles(File folder, WaitingHandler waitingHandler) throws FileNotFoundException, IOException {

        SpectrumFactory spectrumFactory = SpectrumFactory.getInstance();

        for (String spectrumFileName : identification.getSpectrumFiles()) {
            File providedSpectrumLocation = projectDetails.getSpectrumFile(spectrumFileName);
            File projectFolder = cpsFile.getParentFile();
            File dataFolder = new File(projectFolder, "data");
            // try to locate the spectrum file
            if (providedSpectrumLocation == null || !providedSpectrumLocation.exists()) {
                File fileInProjectFolder = new File(projectFolder, spectrumFileName);
                File fileInDataFolder = new File(dataFolder, spectrumFileName);
                File fileInGivenFolder = new File(folder, spectrumFileName);
                if (fileInProjectFolder.exists()) {
                    projectDetails.addSpectrumFile(fileInProjectFolder);
                } else if (fileInDataFolder.exists()) {
                    projectDetails.addSpectrumFile(fileInDataFolder);
                } else if (fileInGivenFolder.exists()) {
                    projectDetails.addSpectrumFile(fileInDataFolder);
                } else {
                    return false;
                }
            }
            File mgfFile = projectDetails.getSpectrumFile(spectrumFileName);
            spectrumFactory.addSpectra(mgfFile, waitingHandler);
        }

        return true;
    }

    /**
     * Loads the spectra in the spectrum factory.
     *
     * @param spectrumFileName the name of the spectrum file to load
     * @param waitingHandler a waiting handler displaying progress to the user.
     * Can be null
     * @throws FileNotFoundException
     * @throws IOException
     *
     * @return a boolean indicating whether the loading was successful
     */
    public boolean loadSpectrumFile(String spectrumFileName, WaitingHandler waitingHandler) throws FileNotFoundException, IOException {

        SpectrumFactory spectrumFactory = SpectrumFactory.getInstance();

        File providedSpectrumLocation = projectDetails.getSpectrumFile(spectrumFileName);
        File projectFolder = cpsFile.getParentFile();
        File dataFolder = new File(projectFolder, "data");

        // try to locate the spectrum file
        if (providedSpectrumLocation == null || !providedSpectrumLocation.exists()) {
            File fileInProjectFolder = new File(projectFolder, spectrumFileName);
            File fileInDataFolder = new File(dataFolder, spectrumFileName);

            if (fileInProjectFolder.exists()) {
                projectDetails.addSpectrumFile(fileInProjectFolder);
            } else if (fileInDataFolder.exists()) {
                projectDetails.addSpectrumFile(fileInDataFolder);
            } else {
                return false;
            }
        }

        File mgfFile = projectDetails.getSpectrumFile(spectrumFileName);
        spectrumFactory.addSpectra(mgfFile, waitingHandler);

        return true;
    }

    /**
     * Imports the gene mapping.
     *
     * @param waitingHandler the waiting handler
     * @param jarFilePath the path to the jar file
     * @return a boolean indicating whether the loading was successful
     */
    public boolean loadGeneMappings(String jarFilePath, WaitingHandler waitingHandler) {
        return genePreferences.loadGeneMappings(jarFilePath, waitingHandler);
    }

    /**
     * Returns the identification object.
     *
     * @return the identification object
     */
    public Identification getIdentification() {
        return identification;
    }

    /**
     * Returns the identification features generator object.
     *
     * @return the identification features generator object
     */
    public IdentificationFeaturesGenerator getIdentificationFeaturesGenerator() {
        return identificationFeaturesGenerator;
    }

    /**
     * Returns the ID filter.
     *
     * @return the ID filter
     */
    public IdFilter getIdFilter() {
        return idFilter;
    }

    /**
     * Returns the annotation preferences.
     *
     * @return the annotation preferences
     */
    public AnnotationPreferences getAnnotationPreferences() {
        return annotationPreferences;
    }

    /**
     * Returns the spectrum counting preferences.
     *
     * @return the spectrum counting preferences
     */
    public SpectrumCountingPreferences getSpectrumCountingPreferences() {
        return spectrumCountingPreferences;
    }

    /**
     * Returns the PTM scoring preferences.
     *
     * @return the PTM scoring preferences
     */
    public PTMScoringPreferences getPtmScoringPreferences() {
        return ptmScoringPreferences;
    }

    /**
     * Returns the project details.
     *
     * @return the project details
     */
    public ProjectDetails getProjectDetails() {
        return projectDetails;
    }

    /**
     * Returns the search parameters.
     *
     * @return the search parameters
     */
    public SearchParameters getSearchParameters() {
        return searchParameters;
    }

    /**
     * Returns the processing preferences.
     *
     * @return the processing preferences
     */
    public ProcessingPreferences getProcessingPreferences() {
        return processingPreferences;
    }

    /**
     * Returns the metrics object.
     *
     * @return the metrics object
     */
    public Metrics getMetrics() {
        return metrics;
    }

    /**
     * Returns the gene preferences.
     *
     * @return the gene preferences
     */
    public GenePreferences getGenePreferences() {
        return genePreferences;
    }

    /**
     * Returns the experiment object.
     *
     * @return the experiment object
     */
    public MsExperiment getExperiment() {
        return experiment;
    }

    /**
     * Returns the sample.
     *
     * @return the sample
     */
    public Sample getSample() {
        return sample;
    }

    /**
     * Returns the replicate number.
     *
     * @return the replicate number
     */
    public int getReplicateNumber() {
        return replicateNumber;
    }

    /**
     * Returns the proteomics analysis object.
     *
     * @return the proteomics analysis object
     */
    public ProteomicAnalysis getProteomicAnalysis() {
        return proteomicAnalysis;
    }

    /**
     * Returns the object cache.
     *
     * @return the object cache
     */
    public ObjectsCache getObjectsCache() {
        return objectsCache;
    }

    /**
     * Returns the filter preferences.
     *
     * @return the filter preferences
     */
    public FilterPreferences getFilterPreferences() {
        return filterPreferences;
    }

    /**
     * Returns the display preferences.
     *
     * @return the display preferences
     */
    public DisplayPreferences getDisplayPreferences() {
        return displayPreferences;
    }

    /**
     * Returns the cps file.
     *
     * @return the cps file
     */
    public File getCpsFile() {
        return cpsFile;
    }

    /**
     * Set the identification feature generator.
     *
     * @param identificationFeaturesGenerator
     */
    public void setIdentificationFeaturesGenerator(IdentificationFeaturesGenerator identificationFeaturesGenerator) {
        this.identificationFeaturesGenerator = identificationFeaturesGenerator;
    }

    /**
     * Set the ID filter.
     *
     * @param idFilter
     */
    public void setIdFilter(IdFilter idFilter) {
        this.idFilter = idFilter;
    }

    /**
     * Set the annotation preferences.
     *
     * @param annotationPreferences
     */
    public void setAnnotationPreferences(AnnotationPreferences annotationPreferences) {
        this.annotationPreferences = annotationPreferences;
    }

    /**
     * Set the spectrum counting preferences.
     *
     * @param spectrumCountingPreferences
     */
    public void setSpectrumCountingPreferences(SpectrumCountingPreferences spectrumCountingPreferences) {
        this.spectrumCountingPreferences = spectrumCountingPreferences;
    }

    /**
     * Set the PTM scoring preferences.
     *
     * @param ptmScoringPreferences
     */
    public void setPtmScoringPreferences(PTMScoringPreferences ptmScoringPreferences) {
        this.ptmScoringPreferences = ptmScoringPreferences;
    }

    /**
     * Set the project details.
     *
     * @param projectDetails
     */
    public void setProjectDetails(ProjectDetails projectDetails) {
        this.projectDetails = projectDetails;
    }

    /**
     * Set the search parameters.
     *
     * @param searchParameters
     */
    public void setSearchParameters(SearchParameters searchParameters) {
        this.searchParameters = searchParameters;
    }

    /**
     * Set the processing preferences.
     *
     * @param processingPreferences
     */
    public void setProcessingPreferences(ProcessingPreferences processingPreferences) {
        this.processingPreferences = processingPreferences;
    }

    /**
     * Returns the sequence matching preferences.
     *
     * @return the sequence matching preferences
     */
    public SequenceMatchingPreferences getSequenceMatchingPreferences() {
        return sequenceMatchingPreferences;
    }

    /**
     * Sets the sequence matching preferences.
     *
     * @param sequenceMatchingPreferences the sequence matching preferences
     */
    public void setSequenceMatchingPreferences(SequenceMatchingPreferences sequenceMatchingPreferences) {
        this.sequenceMatchingPreferences = sequenceMatchingPreferences;
    }

    /**
     * Set the metrics.
     *
     * @param metrics
     */
    public void setMetrics(Metrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Set the gene preferences.
     *
     * @param genePreferences
     */
    public void setGenePreferences(GenePreferences genePreferences) {
        this.genePreferences = genePreferences;
    }

    /**
     * Set the objects cache.
     *
     * @param objectsCache
     */
    public void setObjectsCache(ObjectsCache objectsCache) {
        this.objectsCache = objectsCache;
    }

    /**
     * Set the filter preferences.
     *
     * @param filterPreferences
     */
    public void setFilterPreferences(FilterPreferences filterPreferences) {
        this.filterPreferences = filterPreferences;
    }

    /**
     * Set the display preferences.
     *
     * @param displayPreferences
     */
    public void setDisplayPreferences(DisplayPreferences displayPreferences) {
        this.displayPreferences = displayPreferences;
    }

    /**
     * Set the cps file.
     *
     * @param cpsFile
     */
    public void setCpsFile(File cpsFile) {
        this.cpsFile = cpsFile;
    }

    /**
     * Returns the user preferences.
     *
     * @return the user preferences
     */
    public UserPreferences getUserPreferences() {
        return userPreferences;
    }

    /**
     * Set the identification object.
     *
     * @param identification
     */
    public void setIdentification(Identification identification) {
        this.identification = identification;
    }

    /**
     * Sets the project.
     *
     * @param experiment the experiment
     * @param sample the sample
     * @param replicateNumber the replicate number
     */
    public void setProject(MsExperiment experiment, Sample sample, int replicateNumber) {
        this.experiment = experiment;
        this.sample = sample;
        this.replicateNumber = replicateNumber;
        proteomicAnalysis = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber);
        identification = proteomicAnalysis.getIdentification(IdentificationMethod.MS2_IDENTIFICATION);
    }

    /**
     * Resets the preferences.
     */
    public void clearPreferences() {
        annotationPreferences = new AnnotationPreferences();
        spectrumCountingPreferences = new SpectrumCountingPreferences();
        ptmScoringPreferences = new PTMScoringPreferences();
        filterPreferences = new FilterPreferences();
        displayPreferences = new DisplayPreferences();
        searchParameters = new SearchParameters();
        processingPreferences = new ProcessingPreferences();
        genePreferences = new GenePreferences();
        sequenceMatchingPreferences = null;
        idFilter = new IdFilter();
    }

    /**
     * Set the default preferences.
     */
    public void setDefaultPreferences() {
        searchParameters = new SearchParameters();
        annotationPreferences = new AnnotationPreferences();
        annotationPreferences.setAnnotationLevel(0.75);
        annotationPreferences.useAutomaticAnnotation(true);
        spectrumCountingPreferences = new SpectrumCountingPreferences();
        spectrumCountingPreferences.setSelectedMethod(SpectralCountingMethod.NSAF);
        spectrumCountingPreferences.setValidatedHits(true);
        processingPreferences = new ProcessingPreferences();
        ptmScoringPreferences = new PTMScoringPreferences();
        genePreferences = new GenePreferences();
        sequenceMatchingPreferences = SequenceMatchingPreferences.getDefaultSequenceMatching(searchParameters);
        idFilter = new IdFilter();
    }

    /**
     * Resets the feature generator.
     */
    public void resetIdentificationFeaturesGenerator() {
        identificationFeaturesGenerator = new IdentificationFeaturesGenerator(identification, searchParameters, idFilter, metrics, spectrumCountingPreferences, sequenceMatchingPreferences);
    }

    /**
     * Returns an extended HTML project report.
     *
     * @param waitingHandlerReport the progress report, if null the report from
     * the project details will be used
     * @return an extended HTML project report
     */
    public String getExtendedProjectReport(String waitingHandlerReport) {

        String report = null;

        if (projectDetails != null && getIdentification() != null) {

            report = "<html><br>";
            report += "<b>Experiment</b>: " + experiment.getReference() + "<br>";
            report += "<b>Sample:</b> " + sample.getReference() + "<br>";
            report += "<b>Replicate number:</b> " + replicateNumber + "<br><br>";

            report += "<b>Creation Date:</b> " + projectDetails.getCreationDate() + "<br><br>";

            report += "<b>Identification Files</b>:<br>";
            for (File idFile : projectDetails.getIdentificationFiles()) {
                report += idFile.getAbsolutePath() + " - ";
                HashMap<String, ArrayList<String>> versions = projectDetails.getIdentificationAlgorithmsForFile(idFile.getName());
                ArrayList<String> software = new ArrayList<String>(versions.keySet());
                Collections.sort(software);
                boolean first = true;
                for (String softwareName : software) {
                    if (first) {
                        first = false;
                    } else {
                        report += ", ";
                    }
                    report += softwareName;
                    ArrayList<String> algorithmVersions = versions.get(softwareName);
                    if (algorithmVersions != null && !algorithmVersions.isEmpty()) {
                        report += " - (";
                        boolean firstVersion = true;
                        for (String version : algorithmVersions) {
                            if (firstVersion) {
                                firstVersion = false;
                            } else {
                                report += ", ";
                            }
                            if (version != null) {
                                report += version;
                            } else {
                                report += "unknown version";
                            }
                        }
                        report += ")";
                    }
                }

                report += "<br>";
            }

            report += "<br><b>Spectrum Files:</b><br>";
            for (String mgfFileNames : getIdentification().getSpectrumFiles()) {
                report += projectDetails.getSpectrumFile(mgfFileNames).getAbsolutePath() + "<br>";
            }

            report += "<br><b>FASTA File:</b><br>";
            report += getSearchParameters().getFastaFile().getAbsolutePath() + "<br>";

            report += "<br><br><b>Report:</b><br>";
            if (waitingHandlerReport == null) {
                waitingHandlerReport = projectDetails.getReport();
            }

            if (waitingHandlerReport.lastIndexOf("<br>") == -1) {
                report += "<pre>" + waitingHandlerReport + "</pre>";
            } else {
                report += waitingHandlerReport;
            }

            report += "</html>";
        } else {
            report = "<html><br>";

            report += "<b>Report:</b><br>";
            if (waitingHandlerReport != null) {
                if (waitingHandlerReport.lastIndexOf("<br>") == -1) {
                    report += "<pre>" + waitingHandlerReport + "</pre>";
                } else {
                    report += waitingHandlerReport;
                }
            }

            report += "</html>";
        }

        return report;
    }
}
