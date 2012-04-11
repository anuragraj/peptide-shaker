package eu.isas.peptideshaker;

import com.compomics.util.experiment.MsExperiment;
import com.compomics.util.experiment.ProteomicAnalysis;
import com.compomics.util.experiment.biology.*;
import com.compomics.util.experiment.biology.ions.ReporterIon;
import com.compomics.util.experiment.identification.AdvocateFactory;
import com.compomics.util.experiment.identification.Identification;
import com.compomics.util.experiment.identification.IdentificationMethod;
import com.compomics.util.experiment.identification.ptm.PTMLocationScores;
import com.compomics.util.experiment.identification.PeptideAssumption;
import com.compomics.util.experiment.identification.SequenceFactory;
import com.compomics.util.experiment.identification.identifications.Ms2Identification;
import com.compomics.util.experiment.identification.matches.ModificationMatch;
import com.compomics.util.experiment.identification.matches.PeptideMatch;
import com.compomics.util.experiment.identification.matches.ProteinMatch;
import com.compomics.util.experiment.identification.matches.SpectrumMatch;
import com.compomics.util.experiment.massspectrometry.MSnSpectrum;
import com.compomics.util.experiment.massspectrometry.Spectrum;
import com.compomics.util.experiment.massspectrometry.SpectrumFactory;
import eu.isas.peptideshaker.scoring.InputMap;
import eu.isas.peptideshaker.scoring.PeptideSpecificMap;
import eu.isas.peptideshaker.scoring.ProteinMap;
import eu.isas.peptideshaker.scoring.PsmSpecificMap;
import eu.isas.peptideshaker.scoring.targetdecoy.TargetDecoyMap;
import eu.isas.peptideshaker.scoring.targetdecoy.TargetDecoyResults;
import eu.isas.peptideshaker.fileimport.IdFilter;
import eu.isas.peptideshaker.fileimport.FileImporter;
import eu.isas.peptideshaker.gui.interfaces.WaitingHandler;
import eu.isas.peptideshaker.myparameters.PSMaps;
import eu.isas.peptideshaker.myparameters.PSParameter;
import eu.isas.peptideshaker.myparameters.PSPtmScores;
import eu.isas.peptideshaker.preferences.AnnotationPreferences;
import eu.isas.peptideshaker.preferences.ProjectDetails;
import eu.isas.peptideshaker.scoring.PtmScoring;
import eu.isas.peptideshaker.preferences.SearchParameters;
import eu.isas.peptideshaker.preferences.SpectrumCountingPreferences;
import eu.isas.peptideshaker.utils.IdentificationFeaturesGenerator;
import eu.isas.peptideshaker.utils.Metrics;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import javax.swing.JProgressBar;

/**
 * This class will be responsible for the identification import and the
 * associated calculations.
 *
 * @author Marc Vaudel
 * @author Harald Barsnes
 */
public class PeptideShaker {

    /**
     * If set to true, detailed information is sent to the waiting dialog.
     */
    private boolean detailedReport = false;
    /**
     * The experiment conducted.
     */
    private MsExperiment experiment;
    /**
     * The sample analyzed.
     */
    private Sample sample;
    /**
     * The replicate number.
     */
    private int replicateNumber;
    /**
     * The psm map.
     */
    private PsmSpecificMap psmMap;
    /**
     * The peptide map
     */
    private PeptideSpecificMap peptideMap;
    /**
     * The protein map.
     */
    private ProteinMap proteinMap;
    /**
     * The id importer will import and process the identifications.
     */
    private FileImporter fileImporter = null;
    /**
     * The sequence factory.
     */
    private SequenceFactory sequenceFactory = SequenceFactory.getInstance();
    /**
     * The spectrum factory.
     */
    private SpectrumFactory spectrumFactory = SpectrumFactory.getInstance();
    /**
     * The location of the folder used for serialization of matches.
     */
    public final static String SERIALIZATION_DIRECTORY = "matches";
    /**
     * The compomics PTM factory.
     */
    private PTMFactory ptmFactory = PTMFactory.getInstance();
    /**
     * Metrics to be picked when loading the identification.
     */
    private Metrics metrics = new Metrics();
    /**
     * Map indicating how often a protein was found in a search engine first hit
     * whenever this protein was found more than one time
     */
    private HashMap<String, Integer> proteinCount = new HashMap<String, Integer>();

    /**
     * Constructor without mass specification. Calculation will be done on new
     * maps which will be retrieved as compomics utilities parameters.
     *
     * @param experiment The experiment conducted
     * @param sample The sample analyzed
     * @param replicateNumber The replicate number
     */
    public PeptideShaker(MsExperiment experiment, Sample sample, int replicateNumber) {
        this.experiment = experiment;
        this.sample = sample;
        this.replicateNumber = replicateNumber;
        psmMap = new PsmSpecificMap();
        peptideMap = new PeptideSpecificMap();
        proteinMap = new ProteinMap();
    }

    /**
     * Constructor with map specifications.
     *
     * @param experiment The experiment conducted
     * @param sample The sample analyzed
     * @param replicateNumber The replicate number
     * @param psMaps the peptide shaker maps
     */
    public PeptideShaker(MsExperiment experiment, Sample sample, int replicateNumber, PSMaps psMaps) {
        this.experiment = experiment;
        this.sample = sample;
        this.replicateNumber = replicateNumber;
        this.psmMap = psMaps.getPsmSpecificMap();
        this.peptideMap = psMaps.getPeptideSpecificMap();
        this.proteinMap = psMaps.getProteinMap();
    }

    /**
     * Method used to import identification from identification result files.
     *
     * @param waitingHandler the handler displaying feedback to the user
     * @param idFilter The identification filter to use
     * @param idFiles The files to import
     * @param spectrumFiles The corresponding spectra (can be empty: spectra
     * will not be loaded)
     * @param fastaFile The database file in the fasta format
     * @param searchParameters The search parameters
     * @param annotationPreferences The annotation preferences to use for PTM
     * scoring
     * @param projectDetails The project details
     */
    public void importFiles(WaitingHandler waitingHandler, IdFilter idFilter, ArrayList<File> idFiles, ArrayList<File> spectrumFiles,
            File fastaFile, SearchParameters searchParameters, AnnotationPreferences annotationPreferences, ProjectDetails projectDetails) {

        waitingHandler.appendReport("Import process for " + experiment.getReference() + " (Sample: " + sample.getReference() + ", Replicate: " + replicateNumber + ")\n");

        ProteomicAnalysis analysis = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber);
        analysis.addIdentificationResults(IdentificationMethod.MS2_IDENTIFICATION, new Ms2Identification());
        Identification identification = analysis.getIdentification(IdentificationMethod.MS2_IDENTIFICATION);
        identification.setInMemory(false);
        identification.setAutomatedMemoryManagement(true);
        identification.setSerializationDirectory(SERIALIZATION_DIRECTORY);
        fileImporter = new FileImporter(this, waitingHandler, analysis, idFilter, metrics);
        fileImporter.importFiles(idFiles, spectrumFiles, fastaFile, searchParameters, annotationPreferences);
    }

    /**
     * This method processes the identifications and fills the PeptideShaker
     * maps.
     *
     * @param inputMap The input map
     * @param waitingHandler the handler displaying feedback to the user
     * @param searchParameters
     * @param annotationPreferences
     * @param idFilter
     * @throws IllegalArgumentException
     * @throws IOException
     * @throws Exception
     */
    public void processIdentifications(InputMap inputMap, WaitingHandler waitingHandler, SearchParameters searchParameters, AnnotationPreferences annotationPreferences, IdFilter idFilter)
            throws IllegalArgumentException, IOException, Exception {

        Identification identification = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber).getIdentification(IdentificationMethod.MS2_IDENTIFICATION);
        if (!identification.memoryCheck()) {
            waitingHandler.appendReport("PeptideShaker is encountering memory issues! See http://peptide-shaker.googlecode.com for help.");
        }
        if (waitingHandler.isRunCanceled()) {
            return;
        }
        waitingHandler.appendReport("Computing assumptions probabilities.");
        inputMap.estimateProbabilities(waitingHandler);
        waitingHandler.increaseProgressValue();
        if (waitingHandler.isRunCanceled()) {
            return;
        }
        waitingHandler.appendReport("Saving assumptions probabilities.");
        attachAssumptionsProbabilities(inputMap, waitingHandler);
        waitingHandler.increaseProgressValue();
        if (waitingHandler.isRunCanceled()) {
            return;
        }
        waitingHandler.appendReport("Selecting best peptide per spectrum.");
        fillPsmMap(inputMap, waitingHandler);
        psmMap.cure();
        waitingHandler.increaseProgressValue();
        if (waitingHandler.isRunCanceled()) {
            return;
        }
        waitingHandler.appendReport("Computing PSM probabilities.");
        psmMap.estimateProbabilities(waitingHandler);
        if (waitingHandler.isRunCanceled()) {
            return;
        }
        waitingHandler.appendReport("Saving probabilities, building peptides and proteins.");
        attachSpectrumProbabilitiesAndBuildPeptidesAndProteins(waitingHandler);
        waitingHandler.increaseProgressValue();
        if (waitingHandler.isRunCanceled()) {
            return;
        }
        waitingHandler.appendReport("Generating peptide map.");
        fillPeptideMaps(waitingHandler);
        peptideMap.cure();
        if (waitingHandler.isRunCanceled()) {
            return;
        }
        waitingHandler.appendReport("Computing peptide probabilities.");
        peptideMap.estimateProbabilities(waitingHandler);
        if (waitingHandler.isRunCanceled()) {
            return;
        }
        waitingHandler.appendReport("Saving peptide probabilities.");
        attachPeptideProbabilities(waitingHandler);
        waitingHandler.increaseProgressValue();
        if (waitingHandler.isRunCanceled()) {
            return;
        }
        waitingHandler.appendReport("Generating protein map.");
        fillProteinMap(waitingHandler);
        waitingHandler.increaseProgressValue();
        if (waitingHandler.isRunCanceled()) {
            return;
        }
        waitingHandler.appendReport("Resolving protein inference issues, inferring peptide and protein PI status.");
        cleanProteinGroups(waitingHandler);
        waitingHandler.increaseProgressValue();
        if (waitingHandler.isRunCanceled()) {
            return;
        }
        waitingHandler.appendReport("Correcting protein probabilities.");
        proteinMap.estimateProbabilities(waitingHandler);
        if (waitingHandler.isRunCanceled()) {
            return;
        }
        waitingHandler.appendReport("Saving protein probabilities.");
        attachProteinProbabilities(waitingHandler);
        waitingHandler.increaseProgressValue();
        if (waitingHandler.isRunCanceled()) {
            return;
        }
        waitingHandler.appendReport("Validating identifications at 1% FDR.");
        fdrValidation(waitingHandler);
        waitingHandler.increaseProgressValue();
        if (waitingHandler.isRunCanceled()) {
            return;
        }
        waitingHandler.appendReport("Scoring PTMs in peptides.");
        scorePeptidePtms(waitingHandler, searchParameters, annotationPreferences);
        waitingHandler.increaseProgressValue();
        if (waitingHandler.isRunCanceled()) {
            return;
        }
        waitingHandler.appendReport("Scoring PTMs in proteins.");
        scoreProteinPtms(waitingHandler, searchParameters, annotationPreferences, idFilter);
        waitingHandler.increaseProgressValue();
        if (waitingHandler.isRunCanceled()) {
            return;
        }
        String report = "Identification processing completed.\n\n";
        ArrayList<Integer> suspiciousInput = inputMap.suspiciousInput();
        ArrayList<String> suspiciousPsms = psmMap.suspiciousInput();
        ArrayList<String> suspiciousPeptides = peptideMap.suspiciousInput();
        boolean suspiciousProteins = proteinMap.suspicousInput();

        if (suspiciousInput.size() > 0
                || suspiciousPsms.size() > 0
                || suspiciousPeptides.size() > 0
                || suspiciousProteins) {

            // @TODO: display this in a separate dialog??
            if (detailedReport) {

                report += "The following identification classes retieved non robust statistical estimations, "
                        + "we advice to control the quality of the corresponding matches: \n";

                boolean firstLine = true;

                for (int searchEngine : suspiciousInput) {
                    if (firstLine) {
                        firstLine = false;
                    } else {
                        report += ", ";
                    }
                    report += AdvocateFactory.getInstance().getAdvocate(searchEngine).getName();
                }

                if (suspiciousInput.size() > 0) {
                    report += " identifications.\n";
                }

                firstLine = true;

                for (String fraction : suspiciousPsms) {
                    if (firstLine) {
                        firstLine = false;
                    } else {
                        report += ", ";
                    }
                    report += fraction;
                }

                report += " charged spectra.\n";

                firstLine = true;

                for (String fraction : suspiciousPeptides) {
                    if (firstLine) {
                        firstLine = false;
                    } else {
                        report += ", ";
                    }
                    report += fraction;
                }

                report += " modified peptides.\n";

                if (suspiciousProteins) {
                    report += "proteins. \n";
                }
            }
        }

        waitingHandler.appendReport(report);
        identification.addUrParam(new PSMaps(proteinMap, psmMap, peptideMap));
        waitingHandler.setRunFinished();
    }

    /**
     * Makes a preliminary validation of hits. By default a 1% FDR is used for
     * all maps.
     *
     * @param waitingHandler the handler displaying feedback to the user
     */
    public void fdrValidation(WaitingHandler waitingHandler) {

        TargetDecoyMap currentMap = proteinMap.getTargetDecoyMap();
        TargetDecoyResults currentResults = currentMap.getTargetDecoyResults();
        currentResults.setClassicalEstimators(true);
        currentResults.setClassicalValidation(true);
        currentResults.setFdrLimit(1.0);
        currentMap.getTargetDecoySeries().getFDRResults(currentResults);

        int max = peptideMap.getKeys().size() + psmMap.getKeys().keySet().size();
        waitingHandler.setSecondaryProgressDialogIntermediate(false);
        waitingHandler.setMaxSecondaryProgressValue(max);

        for (String mapKey : peptideMap.getKeys()) {
            if (waitingHandler.isRunCanceled()) {
                return;
            }
            waitingHandler.increaseSecondaryProgressValue();
            currentMap = peptideMap.getTargetDecoyMap(mapKey);
            currentResults = currentMap.getTargetDecoyResults();
            currentResults.setClassicalEstimators(true);
            currentResults.setClassicalValidation(true);
            currentResults.setFdrLimit(1.0);
            currentMap.getTargetDecoySeries().getFDRResults(currentResults);
        }

        for (int mapKey : psmMap.getKeys().keySet()) {
            if (waitingHandler.isRunCanceled()) {
                return;
            }
            waitingHandler.increaseSecondaryProgressValue();
            currentMap = psmMap.getTargetDecoyMap(mapKey);
            currentResults = currentMap.getTargetDecoyResults();
            currentResults.setClassicalEstimators(true);
            currentResults.setClassicalValidation(true);
            currentResults.setFdrLimit(1.0);
            currentMap.getTargetDecoySeries().getFDRResults(currentResults);
        }

        waitingHandler.setSecondaryProgressDialogIntermediate(false);

        validateIdentifications(waitingHandler.getSecondaryProgressBar());
        waitingHandler.setSecondaryProgressDialogIntermediate(true);
    }

    /**
     * Processes the identifications if a change occured in the psm map.
     *
     * @param waitingHandler the waiting handler
     * @throws Exception Exception thrown whenever it is attempted to attach
     * more than one identification per search engine per spectrum
     */
    public void spectrumMapChanged(WaitingHandler waitingHandler) throws Exception {
        peptideMap = new PeptideSpecificMap();
        proteinMap = new ProteinMap();
        attachSpectrumProbabilitiesAndBuildPeptidesAndProteins(waitingHandler);
        fillPeptideMaps(waitingHandler);
        peptideMap.cure();
        peptideMap.estimateProbabilities(waitingHandler);
        attachPeptideProbabilities(waitingHandler);
        fillProteinMap(waitingHandler);
        proteinMap.estimateProbabilities(waitingHandler);
        attachProteinProbabilities(waitingHandler);
        cleanProteinGroups(waitingHandler);
    }

    /**
     * Processes the identifications if a change occured in the peptide map.
     *
     * @param waitingHandler the waiting handler
     * @throws Exception Exception thrown whenever it is attempted to attach
     * more than one identification per search engine per spectrum
     */
    public void peptideMapChanged(WaitingHandler waitingHandler) throws Exception {
        proteinMap = new ProteinMap();
        attachPeptideProbabilities(waitingHandler);
        fillProteinMap(waitingHandler);
        proteinMap.estimateProbabilities(waitingHandler);
        attachProteinProbabilities(waitingHandler);
        cleanProteinGroups(waitingHandler);
    }

    /**
     * Processes the identifications if a change occured in the protein map.
     *
     * @param waitingHandler the waiting handler
     */
    public void proteinMapChanged(WaitingHandler waitingHandler) {
        attachProteinProbabilities(waitingHandler);
    }

    /**
     * This method will flag validated identifications.
     *
     * @param progressBar the progress bar
     */
    public void validateIdentifications(JProgressBar progressBar) {

        Identification identification = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber).getIdentification(IdentificationMethod.MS2_IDENTIFICATION);
        PSParameter psParameter = new PSParameter();

        if (progressBar != null) {
            progressBar.setMaximum(identification.getProteinIdentification().size()
                    + identification.getPeptideIdentification().size()
                    + identification.getSpectrumIdentification().size());
        }

        double proteinThreshold = proteinMap.getTargetDecoyMap().getTargetDecoyResults().getScoreLimit();
        boolean noValidated = proteinMap.getTargetDecoyMap().getTargetDecoyResults().noValidated();
        for (String proteinKey : identification.getProteinIdentification()) {
            psParameter = (PSParameter) identification.getMatchParameter(proteinKey, psParameter);
            if (!noValidated && psParameter.getProteinProbabilityScore() <= proteinThreshold) {
                psParameter.setValidated(true);
            } else {
                psParameter.setValidated(false);
            }
            if (progressBar != null) {
                progressBar.setValue(progressBar.getValue() + 1);
            }
        }

        for (String peptideKey : identification.getPeptideIdentification()) {
            psParameter = (PSParameter) identification.getMatchParameter(peptideKey, psParameter);
            double peptideThreshold = peptideMap.getTargetDecoyMap(peptideMap.getCorrectedKey(psParameter.getSecificMapKey())).getTargetDecoyResults().getScoreLimit();
            noValidated = peptideMap.getTargetDecoyMap(peptideMap.getCorrectedKey(psParameter.getSecificMapKey())).getTargetDecoyResults().noValidated();
            if (!noValidated && psParameter.getPeptideProbabilityScore() <= peptideThreshold) {
                psParameter.setValidated(true);
            } else {
                psParameter.setValidated(false);
            }
            if (progressBar != null) {
                progressBar.setValue(progressBar.getValue() + 1);
            }
        }

        for (String spectrumKey : identification.getSpectrumIdentification()) {
            psParameter = (PSParameter) identification.getMatchParameter(spectrumKey, psParameter);
            double psmThreshold = psmMap.getTargetDecoyMap(psmMap.getCorrectedKey(psParameter.getSecificMapKey())).getTargetDecoyResults().getScoreLimit();
            noValidated = psmMap.getTargetDecoyMap(psmMap.getCorrectedKey(psParameter.getSecificMapKey())).getTargetDecoyResults().noValidated();
            if (!noValidated && psParameter.getPsmProbabilityScore() <= psmThreshold) {
                psParameter.setValidated(true);
            } else {
                psParameter.setValidated(false);
            }
            if (progressBar != null) {
                progressBar.setValue(progressBar.getValue() + 1);
            }
        }
    }

    /**
     * Fills the psm specific map.
     *
     * @param inputMap The input map
     * @param waitingHandler the handler displaying feedback to the user
     */
    private void fillPsmMap(InputMap inputMap, WaitingHandler waitingHandler) throws Exception {

        Identification identification = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber).getIdentification(IdentificationMethod.MS2_IDENTIFICATION);
        int max = identification.getSpectrumIdentification().size();
        waitingHandler.setSecondaryProgressDialogIntermediate(false);
        waitingHandler.setMaxSecondaryProgressValue(max);
        ArrayList<String> identifications;
        // map of the first hits for this spectrum: score -> max protein count -> max search engine votes
        HashMap<Double, HashMap<Integer, HashMap<Integer, PeptideAssumption>>> peptideAssumptions;
        PSParameter psParameter, psParameter2;
        PeptideAssumption bestAssumption;
        SpectrumMatch spectrumMatch;
        ArrayList<Double> eValues2;
        boolean found, multiSE = inputMap.isMultipleSearchEngines();
        String id;
        double p, bestEvalue;
        int nSE, proteinMax;
        Integer tempCount;

        for (String spectrumKey : identification.getSpectrumIdentification()) {
            psParameter = new PSParameter();
            identifications = new ArrayList<String>();
            peptideAssumptions = new HashMap<Double, HashMap<Integer, HashMap<Integer, PeptideAssumption>>>();
            spectrumMatch = identification.getSpectrumMatch(spectrumKey);

            for (int searchEngine1 : spectrumMatch.getAdvocates()) {
                bestEvalue = Collections.min(spectrumMatch.getAllAssumptions(searchEngine1).keySet());
                for (PeptideAssumption peptideAssumption1 : spectrumMatch.getAllAssumptions(searchEngine1).get(bestEvalue)) {
                    peptideAssumption1 = spectrumMatch.getFirstHit(searchEngine1);
                    id = peptideAssumption1.getPeptide().getKey();
                    if (!identifications.contains(id)) {
                        psParameter = (PSParameter) peptideAssumption1.getUrParam(psParameter);
                        if (multiSE) {
                            p = psParameter.getSearchEngineProbability();
                        } else {
                            p = peptideAssumption1.getEValue();
                        }
                        nSE = 1;
                        proteinMax = 1;
                        for (String protein : peptideAssumption1.getPeptide().getParentProteins()) {
                            tempCount = proteinCount.get(protein);
                            if (tempCount != null && tempCount > proteinMax) {
                                proteinMax = tempCount;
                            }
                        }
                        for (int searchEngine2 : spectrumMatch.getAdvocates()) {
                            if (searchEngine1 != searchEngine2) {
                                found = false;
                                eValues2 = new ArrayList<Double>(spectrumMatch.getAllAssumptions(searchEngine2).keySet());
                                Collections.sort(eValues2);
                                for (double eValue2 : eValues2) {
                                    for (PeptideAssumption peptideAssumption2 : spectrumMatch.getAllAssumptions(searchEngine2).get(eValue2)) {
                                        if (id.equals(peptideAssumption2.getPeptide().getKey())) {
                                            psParameter2 = (PSParameter) peptideAssumption2.getUrParam(psParameter);
                                            p = p * psParameter2.getSearchEngineProbability();
                                            nSE++;
                                            found = true;
                                            break;
                                        }
                                    }
                                    if (found) {
                                        break;
                                    }
                                }
                            }
                        }
                        identifications.add(id);
                        if (!peptideAssumptions.containsKey(p)) {
                            peptideAssumptions.put(p, new HashMap<Integer, HashMap<Integer, PeptideAssumption>>());
                        }
                        if (!peptideAssumptions.get(p).containsKey(proteinMax)) {
                            peptideAssumptions.get(p).put(proteinMax, new HashMap<Integer, PeptideAssumption>());
                        }
                        if (!peptideAssumptions.get(p).get(proteinMax).containsKey(nSE)) {
                            peptideAssumptions.get(p).get(proteinMax).put(nSE, peptideAssumption1);
                        }
                    }
                }
            }

            p = Collections.min(peptideAssumptions.keySet());
            proteinMax = Collections.max(peptideAssumptions.get(p).keySet());
            nSE = Collections.max(peptideAssumptions.get(p).get(proteinMax).keySet());
            bestAssumption = peptideAssumptions.get(p).get(proteinMax).get(nSE);
            spectrumMatch.setFirstHit(bestAssumption.getAdvocate(), bestAssumption);
            spectrumMatch.setBestAssumption(bestAssumption);
            psParameter = new PSParameter();
            psParameter.setSpectrumProbabilityScore(p);
            psmMap.addPoint(p, spectrumMatch);
            psParameter.setSecificMapKey(psmMap.getKey(spectrumMatch) + "");
            identification.addMatchParameter(spectrumKey, psParameter);
            identification.setMatchChanged(spectrumMatch);
            waitingHandler.increaseSecondaryProgressValue();
            if (waitingHandler.isRunCanceled()) {
                return;
            }
        }

        // the protein count map is no longer needed
        proteinCount.clear();

        waitingHandler.setSecondaryProgressDialogIntermediate(true);
    }

    /**
     * Attaches the spectrum posterior error probabilities to the peptide
     * assumptions.
     *
     * @param inputMap
     * @param waitingHandler the handler displaying feedback to the user
     */
    private void attachAssumptionsProbabilities(InputMap inputMap, WaitingHandler waitingHandler) throws Exception {
        Identification identification = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber).getIdentification(IdentificationMethod.MS2_IDENTIFICATION);

        int max = identification.getSpectrumIdentification().size();
        waitingHandler.setSecondaryProgressDialogIntermediate(false);
        waitingHandler.setMaxSecondaryProgressValue(max);

        for (String spectrumKey : identification.getSpectrumIdentification()) {

            waitingHandler.increaseSecondaryProgressValue();

            SpectrumMatch spectrumMatch = identification.getSpectrumMatch(spectrumKey);

            for (int searchEngine : spectrumMatch.getAdvocates()) {

                ArrayList<Double> eValues = new ArrayList<Double>(spectrumMatch.getAllAssumptions(searchEngine).keySet());
                Collections.sort(eValues);
                double previousP = 0;

                for (double eValue : eValues) {

                    for (PeptideAssumption peptideAssumption : spectrumMatch.getAllAssumptions(searchEngine).get(eValue)) {

                        PSParameter psParameter = new PSParameter();
                        double newP = inputMap.getProbability(searchEngine, eValue);

                        if (newP > previousP) {
                            psParameter.setSearchEngineProbability(newP);
                            previousP = newP;
                        } else {
                            psParameter.setSearchEngineProbability(previousP);
                        }

                        peptideAssumption.addUrParam(psParameter);
                    }
                }
            }

            identification.setMatchChanged(spectrumMatch);
            if (waitingHandler.isRunCanceled()) {
                return;
            }
        }

        waitingHandler.setSecondaryProgressDialogIntermediate(true);
    }

    /**
     * Attaches the spectrum posterior error probabilities to the spectrum
     * matches.
     *
     * @param waitingHandler the handler displaying feedback to the user
     */
    private void attachSpectrumProbabilitiesAndBuildPeptidesAndProteins(WaitingHandler waitingHandler) {

        waitingHandler.setTitle("Attaching Spectrum Probabilities. Please Wait...");

        Identification identification = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber).getIdentification(IdentificationMethod.MS2_IDENTIFICATION);

        waitingHandler.setSecondaryProgressDialogIntermediate(false);
        waitingHandler.setMaxSecondaryProgressValue(identification.getSpectrumIdentification().size());

        PSParameter psParameter = new PSParameter();

        for (String spectrumKey : identification.getSpectrumIdentification()) {

            psParameter = (PSParameter) identification.getMatchParameter(spectrumKey, psParameter);
            psParameter.setPsmProbability(psmMap.getProbability(psParameter.getSecificMapKey(), psParameter.getPsmProbabilityScore()));

            waitingHandler.increaseSecondaryProgressValue();

            identification.buildPeptidesAndProteins(spectrumKey);

            if (waitingHandler.isRunCanceled()) {
                return;
            }
        }

        waitingHandler.setSecondaryProgressDialogIntermediate(true);
    }

    /**
     * Attaches scores to possible PTM locations to spectrum matches.
     *
     * @param inspectedSpectra
     * @param waitingHandler the handler displaying feedback to the user
     * @param searchParameters
     * @param annotationPreferences
     * @throws Exception
     */
    public void scorePSMPTMs(ArrayList<String> inspectedSpectra, WaitingHandler waitingHandler, SearchParameters searchParameters, AnnotationPreferences annotationPreferences) throws Exception {
        Identification identification = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber).getIdentification(IdentificationMethod.MS2_IDENTIFICATION);
        SpectrumMatch spectrumMatch;

        int max = inspectedSpectra.size();
        waitingHandler.setSecondaryProgressDialogIntermediate(false);
        waitingHandler.setMaxSecondaryProgressValue(max);

        for (String spectrumKey : inspectedSpectra) {
            waitingHandler.increaseSecondaryProgressValue();
            spectrumMatch = identification.getSpectrumMatch(spectrumKey);
            scorePTMs(spectrumMatch, searchParameters, annotationPreferences);
        }

        waitingHandler.setSecondaryProgressDialogIntermediate(true);
    }

    /**
     * Scores the PTMs of all validated peptides.
     *
     * @param waitingHandler the handler displaying feedback to the user
     * @param searchParameters the search parameters
     * @param annotationPreferences the annotation preferences
     * @throws Exception exception thrown whenever a problem occurred while
     * deserializing a match
     */
    public void scorePeptidePtms(WaitingHandler waitingHandler, SearchParameters searchParameters, AnnotationPreferences annotationPreferences) throws Exception {
        Identification identification = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber).getIdentification(IdentificationMethod.MS2_IDENTIFICATION);
        PeptideMatch peptideMatch;

        int max = identification.getPeptideIdentification().size();
        waitingHandler.setSecondaryProgressDialogIntermediate(false);
        waitingHandler.setMaxSecondaryProgressValue(max);

        for (String peptideKey : identification.getPeptideIdentification()) {
            peptideMatch = identification.getPeptideMatch(peptideKey);
            scorePTMs(peptideMatch, searchParameters, annotationPreferences);
            waitingHandler.increaseSecondaryProgressValue();
            if (waitingHandler.isRunCanceled()) {
                return;
            }
        }

        waitingHandler.setSecondaryProgressDialogIntermediate(true);
    }

    /**
     * Scores the PTMs of all validated proteins.
     *
     * @param waitingHandler the handler displaying feedback to the user
     * @param searchParameters the search parameters
     * @param annotationPreferences the annotation preferences
     * @throws Exception exception thrown whenever a problem occurred while
     * deserializing a match
     */
    public void scoreProteinPtms(WaitingHandler waitingHandler, SearchParameters searchParameters, AnnotationPreferences annotationPreferences) throws Exception {
        scoreProteinPtms(waitingHandler, searchParameters, annotationPreferences, null);
    }

    /**
     * Scores the PTMs of all validated proteins.
     *
     * @param waitingHandler the handler displaying feedback to the user
     * @param searchParameters the search parameters
     * @param annotationPreferences the annotation preferences
     * @param idFilter the identification filter, needed only to get max values
     * for the metrics, can be null when rescoring PTMs
     * @throws Exception exception thrown whenever a problem occurred while
     * deserializing a match
     */
    private void scoreProteinPtms(WaitingHandler waitingHandler, SearchParameters searchParameters, AnnotationPreferences annotationPreferences, IdFilter idFilter) throws Exception {
        Identification identification = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber).getIdentification(IdentificationMethod.MS2_IDENTIFICATION);
        ProteinMatch proteinMatch;

        int max = identification.getProteinIdentification().size();
        waitingHandler.setSecondaryProgressDialogIntermediate(false);
        waitingHandler.setMaxSecondaryProgressValue(max);

        // If needed, while we are iterating proteins, we will take the maximal spectrum counting value and number of validated proteins as well.
        // The spectrum counting preferences are here the default preferences.
        int nValidatedProteins = 0;
        SpectrumCountingPreferences tempPreferences = new SpectrumCountingPreferences();
        PSParameter psParameter = new PSParameter();
        double tempSpectrumCounting, maxSpectrumCounting = 0;
        Enzyme enzyme = searchParameters.getEnzyme();
        int maxPepLength = idFilter.getMaxPepLength();
        for (String proteinKey : identification.getProteinIdentification()) {
            proteinMatch = identification.getProteinMatch(proteinKey);
            scorePTMs(proteinMatch, searchParameters, annotationPreferences, false);

            if (metrics != null) {
                psParameter = (PSParameter) identification.getMatchParameter(proteinKey, psParameter);
                if (psParameter.isValidated()) {
                    nValidatedProteins++;
                }
                tempSpectrumCounting = IdentificationFeaturesGenerator.estimateSpectrumCounting(identification, sequenceFactory, proteinKey, tempPreferences, enzyme, maxPepLength);
                if (tempSpectrumCounting > maxSpectrumCounting) {
                    maxSpectrumCounting = tempSpectrumCounting;
                }
            }
            waitingHandler.increaseSecondaryProgressValue();
            if (waitingHandler.isRunCanceled()) {
                return;
            }
        }
        if (metrics != null) {
            metrics.setMaxSpectrumCounting(maxSpectrumCounting);
            metrics.setnValidatedProteins(nValidatedProteins);
        }

        waitingHandler.setSecondaryProgressDialogIntermediate(true);
    }

    /**
     * Scores ptms in a protein match.
     *
     * @param proteinMatch the protein match
     * @param searchParameters the search parameters
     * @param annotationPreferences the annotation preferences
     * @param scorePeptides if true peptide scores will be recalculated
     * @throws Exception exception thrown whenever an error occurred while
     * deserilalizing a match
     */
    public void scorePTMs(ProteinMatch proteinMatch, SearchParameters searchParameters, AnnotationPreferences annotationPreferences, boolean scorePeptides) throws Exception {

        PSPtmScores proteinScores = new PSPtmScores();
        PtmScoring ptmScoring;
        PSParameter psParameter = new PSParameter();
        Identification identification = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber).getIdentification(IdentificationMethod.MS2_IDENTIFICATION);
        PeptideMatch peptideMath;
        String peptideSequence, proteinSequence = null;
        for (String peptideKey : proteinMatch.getPeptideMatches()) {
            psParameter = (PSParameter) identification.getMatchParameter(peptideKey, psParameter);
            if (psParameter.isValidated() && Peptide.isModified(peptideKey)) {
                peptideMath = identification.getPeptideMatch(peptideKey);
                peptideSequence = Peptide.getSequence(peptideKey);
                if (peptideMath.getUrParam(new PSPtmScores()) == null || scorePeptides) {
                    scorePTMs(peptideMath, searchParameters, annotationPreferences);
                }
                PSPtmScores peptideScores = (PSPtmScores) peptideMath.getUrParam(new PSPtmScores());
                if (peptideScores != null) {
                    for (String modification : peptideScores.getScoredPTMs()) {
                        if (proteinSequence == null) {
                            proteinSequence = sequenceFactory.getProtein(proteinMatch.getMainMatch()).getSequence();
                        }
                        ptmScoring = peptideScores.getPtmScoring(modification);
                        for (int pos : getProteinModificationIndexes(proteinSequence, peptideSequence, ptmScoring.getPtmLocation())) {
                            proteinScores.addMainModificationSite(modification, pos);
                        }
                        for (int pos : getProteinModificationIndexes(proteinSequence, peptideSequence, ptmScoring.getSecondaryPtmLocations())) {
                            proteinScores.addSecondaryModificationSite(modification, pos);
                        }
                    }
                }
            }
        }

        proteinMatch.addUrParam(proteinScores);
        identification.setMatchChanged(proteinMatch);
    }

    /**
     * Returns the protein indexes of a modification found in a peptide.
     *
     * @param proteinSequence The protein sequence
     * @param peptideSequence The peptide sequence
     * @param positionInPeptide The position(s) of the modification in the
     * peptide sequence
     * @return the possible modification sites in a protein
     */
    public static ArrayList<Integer> getProteinModificationIndexes(String proteinSequence, String peptideSequence, ArrayList<Integer> positionInPeptide) {
        ArrayList<Integer> result = new ArrayList<Integer>();
        String tempSequence = proteinSequence;

        while (tempSequence.lastIndexOf(peptideSequence) >= 0) {
            int peptideTempStart = tempSequence.lastIndexOf(peptideSequence) + 1;
            for (int pos : positionInPeptide) {
                result.add(peptideTempStart + pos - 2);
            }
            tempSequence = proteinSequence.substring(0, peptideTempStart);
        }
        return result;
    }

    /**
     * Scores the PTMs for a peptide match.
     *
     * @param peptideMatch the peptide match of interest
     * @param searchParameters
     * @param annotationPreferences
     * @throws Exception exception thrown whenever an error occurred while
     * deserializing a match
     */
    public void scorePTMs(PeptideMatch peptideMatch, SearchParameters searchParameters, AnnotationPreferences annotationPreferences) throws Exception {
        Identification identification = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber).getIdentification(IdentificationMethod.MS2_IDENTIFICATION);
        PSPtmScores psmScores, peptideScores = new PSPtmScores();
        PSParameter psParameter = new PSParameter();
        ArrayList<String> variableModifications = new ArrayList<String>();

        for (ModificationMatch modificationMatch : peptideMatch.getTheoreticPeptide().getModificationMatches()) {
            if (modificationMatch.isVariable()) {
                PTM ptm = ptmFactory.getPTM(modificationMatch.getTheoreticPtm());
                if (ptm.getType() == PTM.MODAA
                        && !variableModifications.contains(modificationMatch.getTheoreticPtm())) {
                    variableModifications.add(modificationMatch.getTheoreticPtm());
                }
            }
        }

        if (variableModifications.size() > 0) {
            boolean validated = false;
            double bestConfidence = 0;
            ArrayList<String> bestKeys = new ArrayList<String>();
            for (String spectrumKey : peptideMatch.getSpectrumMatches()) {
                psParameter = (PSParameter) identification.getMatchParameter(spectrumKey, psParameter);
                if (psParameter.isValidated()) {
                    if (!validated) {
                        validated = true;
                        bestKeys.clear();
                    }
                    bestKeys.add(spectrumKey);
                } else if (!validated) {
                    if (psParameter.getPsmConfidence() > bestConfidence) {
                        bestConfidence = psParameter.getPsmConfidence();
                        bestKeys.clear();
                        bestKeys.add(spectrumKey);
                    } else if (psParameter.getPsmConfidence() == bestConfidence) {
                        bestKeys.add(spectrumKey);
                    }
                }
            }
            for (String spectrumKey : bestKeys) {
                SpectrumMatch spectrumMatch = identification.getSpectrumMatch(spectrumKey);
                scorePTMs(spectrumMatch, searchParameters, annotationPreferences);
                for (String modification : variableModifications) {
                    if (!peptideScores.containsPtm(modification)) {
                        peptideScores.addPtmScoring(modification, new PtmScoring(modification));
                    }
                    psmScores = (PSPtmScores) spectrumMatch.getUrParam(new PSPtmScores());
                    if (psmScores != null) {
                        PtmScoring spectrumScoring = psmScores.getPtmScoring(modification);
                        if (spectrumScoring != null) {
                            peptideScores.getPtmScoring(modification).addAll(spectrumScoring);
                        }
                    }
                }
            }
            for (String modification : variableModifications) {
                PtmScoring scoring = peptideScores.getPtmScoring(modification);
                if (scoring != null) {
                    for (int mainLocation : scoring.getPtmLocation()) {
                        peptideScores.addMainModificationSite(modification, mainLocation);
                    }
                    for (int secondaryLocation : scoring.getSecondaryPtmLocations()) {
                        peptideScores.addSecondaryModificationSite(modification, secondaryLocation);
                    }
                }
            }

            peptideMatch.addUrParam(peptideScores);
            identification.setMatchChanged(peptideMatch);
        }
    }

    /**
     * Scores PTM locations for a desired spectrumMatch.
     *
     * @param spectrumMatch The spectrum match of interest
     * @param searchParameters
     * @param annotationPreferences
     * @throws Exception exception thrown whenever an error occurred while
     * reading/writing the an identification match
     */
    public void scorePTMs(SpectrumMatch spectrumMatch, SearchParameters searchParameters, AnnotationPreferences annotationPreferences) throws Exception {

        attachDeltaScore(spectrumMatch);
        attachAScore(spectrumMatch, searchParameters, annotationPreferences);
        PSPtmScores ptmScores = (PSPtmScores) spectrumMatch.getUrParam(new PSPtmScores());

        if (ptmScores != null) {

            PtmScoring ptmScoring;

            for (String modification : ptmScores.getScoredPTMs()) {
                ptmScoring = ptmScores.getPtmScoring(modification);
                String bestAKey = ptmScoring.getBestAScoreLocations();
                String bestDKey = ptmScoring.getBestDeltaScoreLocations();
                String retainedKey;
                int confidence = PtmScoring.RANDOM;

                if (bestAKey != null) {
                    retainedKey = bestAKey;
                    if (ptmScoring.getAScore(bestAKey) <= 50) {
                        if (bestAKey.equals(bestDKey)) {
                            confidence = PtmScoring.DOUBTFUL;
                            if (ptmScoring.getDeltaScore(bestDKey) > 50) {
                                confidence = PtmScoring.CONFIDENT;
                            }
                        }
                    } else if (bestAKey.equals(bestDKey)) {
                        confidence = PtmScoring.VERY_CONFIDENT;
                    } else {
                        confidence = PtmScoring.CONFIDENT;
                    }
                } else {
                    retainedKey = bestDKey;
                    if (ptmScoring.getDeltaScore(bestDKey) > 50) {
                        confidence = PtmScoring.CONFIDENT;
                    } else {
                        confidence = PtmScoring.DOUBTFUL;
                    }
                }
                if (retainedKey != null) {
                    ptmScoring.setPtmSite(retainedKey, confidence);
                } else {
                    int debug = 1;
                }
            }
        }
    }

    /**
     * Scores the PTM locations using the delta score.
     *
     * @param spectrumMatch the spectrum match of interest
     * @throws Exception exception thrown whenever an error occurred while
     * reading/writing the an identification match
     */
    private void attachDeltaScore(SpectrumMatch spectrumMatch) throws Exception {
        Identification identification = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber).getIdentification(IdentificationMethod.MS2_IDENTIFICATION);

        PSParameter psParameter = new PSParameter();
        double p1, p2;
        String mainSequence, modificationName;
        ArrayList<String> modifications;
        HashMap<String, ArrayList<Integer>> modificationProfiles = new HashMap<String, ArrayList<Integer>>();
        PSPtmScores ptmScores;
        PtmScoring ptmScoring;
        ptmScores = new PSPtmScores();

        if (spectrumMatch.getUrParam(new PSPtmScores()) != null) {
            ptmScores = (PSPtmScores) spectrumMatch.getUrParam(new PSPtmScores());
        }

        psParameter = (PSParameter) spectrumMatch.getBestAssumption().getUrParam(psParameter);
        p1 = psParameter.getSearchEngineProbability();

        if (p1 < 1) {
            mainSequence = spectrumMatch.getBestAssumption().getPeptide().getSequence();
            p2 = 1;
            modifications = new ArrayList<String>();
            for (ModificationMatch modificationMatch : spectrumMatch.getBestAssumption().getPeptide().getModificationMatches()) {
                if (modificationMatch.isVariable()) {
                    PTM ptm = ptmFactory.getPTM(modificationMatch.getTheoreticPtm());
                    if (ptm.getType() == PTM.MODAA) {
                        modificationName = modificationMatch.getTheoreticPtm();
                        if (!modifications.contains(modificationName)) {
                            modifications.add(modificationName);
                            modificationProfiles.put(modificationName, new ArrayList<Integer>());
                        }
                        modificationProfiles.get(modificationName).add(modificationMatch.getModificationSite());
                    }
                }
            }
            if (!modifications.isEmpty()) {
                for (String mod : modifications) {
                    for (PeptideAssumption peptideAssumption : spectrumMatch.getAllAssumptions()) {
                        if (peptideAssumption.getRank() > 1
                                && peptideAssumption.getPeptide().getSequence().equals(mainSequence)) {
                            boolean newLocation = false;
                            for (ModificationMatch modMatch : peptideAssumption.getPeptide().getModificationMatches()) {
                                if (modMatch.getTheoreticPtm().equals(mod)
                                        && !modificationProfiles.get(mod).contains(modMatch.getModificationSite())) {
                                    newLocation = true;
                                    break;
                                }
                            }
                            if (newLocation) {
                                psParameter = (PSParameter) peptideAssumption.getUrParam(psParameter);
                                if (psParameter.getSearchEngineProbability() < p2) {
                                    p2 = psParameter.getSearchEngineProbability();
                                }
                            }
                        }
                    }
                    ptmScoring = ptmScores.getPtmScoring(mod);
                    if (ptmScoring == null) {
                        ptmScoring = new PtmScoring(mod);
                    }
                    ptmScoring.addDeltaScore(modificationProfiles.get(mod), (p2 - p1) * 100);
                    ptmScores.addPtmScoring(mod, ptmScoring);
                }
                spectrumMatch.addUrParam(ptmScores);
                identification.setMatchChanged(spectrumMatch);
            }
        }
    }

    /**
     * Attach the a-score.
     *
     * @param spectrumMatch
     * @param searchParameters
     * @param annotationPreferences
     * @throws Exception
     */
    private void attachAScore(SpectrumMatch spectrumMatch, SearchParameters searchParameters, AnnotationPreferences annotationPreferences) throws Exception {

        Identification identification = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber).getIdentification(IdentificationMethod.MS2_IDENTIFICATION);

        PSParameter psParameter = new PSParameter();
        double p1;
        String modificationName;
        HashMap<String, PTM> modifications;
        PSPtmScores ptmScores;
        PtmScoring ptmScoring;
        ptmScores = new PSPtmScores();

        if (spectrumMatch.getUrParam(new PSPtmScores()) != null) {
            ptmScores = (PSPtmScores) spectrumMatch.getUrParam(new PSPtmScores());
        }

        psParameter = (PSParameter) spectrumMatch.getBestAssumption().getUrParam(psParameter);
        p1 = psParameter.getSearchEngineProbability();

        if (p1 < 1) {
            modifications = new HashMap<String, PTM>();
            HashMap<String, Integer> nMod = new HashMap<String, Integer>();

            for (ModificationMatch modificationMatch : spectrumMatch.getBestAssumption().getPeptide().getModificationMatches()) {
                if (modificationMatch.isVariable()) {
                    PTM ptm = ptmFactory.getPTM(modificationMatch.getTheoreticPtm());
                    if (ptm.getType() == PTM.MODAA) {
                        modificationName = modificationMatch.getTheoreticPtm();
                        if (!modifications.keySet().contains(modificationName)) {
                            modifications.put(modificationName, ptm);
                            nMod.put(modificationName, 1);
                        } else {
                            nMod.put(modificationName, nMod.get(modificationName) + 1);
                        }
                    }
                }
            }

            if (!modifications.isEmpty()) {
                MSnSpectrum spectrum = (MSnSpectrum) spectrumFactory.getSpectrum(spectrumMatch.getKey());
                annotationPreferences.setCurrentSettings(spectrumMatch.getBestAssumption().getPeptide(), spectrumMatch.getBestAssumption().getIdentificationCharge().value, true);
                for (String mod : modifications.keySet()) {
                    if (nMod.get(mod) == 1) {
                        HashMap<ArrayList<Integer>, Double> aScores = PTMLocationScores.getAScore(spectrumMatch.getBestAssumption().getPeptide(),
                                modifications.get(mod), nMod.get(mod), spectrum, annotationPreferences.getIonTypes(),
                                annotationPreferences.getNeutralLosses(), annotationPreferences.getValidatedCharges(),
                                spectrumMatch.getBestAssumption().getIdentificationCharge().value,
                                searchParameters.getFragmentIonAccuracy());
                        ptmScoring = ptmScores.getPtmScoring(mod);
                        if (ptmScoring == null) {
                            ptmScoring = new PtmScoring(mod);
                        }
                        for (ArrayList<Integer> modificationProfile : aScores.keySet()) {
                            ptmScoring.addAScore(modificationProfile, aScores.get(modificationProfile));
                        }
                        ptmScores.addPtmScoring(mod, ptmScoring);
                    }
                }
                spectrumMatch.addUrParam(ptmScores);
                identification.setMatchChanged(spectrumMatch);
            }
        }
    }

    /**
     * Fills the peptide specific map.
     *
     * @param waitingHandler the handler displaying feedback to the user
     * @throws Exception
     */
    private void fillPeptideMaps(WaitingHandler waitingHandler) throws Exception {

        waitingHandler.setTitle("Filling Peptide Maps. Please Wait...");

        Identification identification = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber).getIdentification(IdentificationMethod.MS2_IDENTIFICATION);
        PSParameter psParameter = new PSParameter();

        waitingHandler.setSecondaryProgressDialogIntermediate(false);
        waitingHandler.setMaxSecondaryProgressValue(identification.getPeptideIdentification().size());

        HashMap<String, Double> fractionScores;
        String fraction;
        ArrayList<String> foundModifications = new ArrayList<String>();

        for (String peptideKey : identification.getPeptideIdentification()) {
            for (String modification : Peptide.getModificationFamily(peptideKey)) {
                if (!foundModifications.contains(modification)) {
                    foundModifications.add(modification);
                }
            }
            double probaScore = 1;
            fractionScores = new HashMap<String, Double>();
            PeptideMatch peptideMatch = identification.getPeptideMatch(peptideKey);
            for (String spectrumKey : peptideMatch.getSpectrumMatches()) {
                psParameter = (PSParameter) identification.getMatchParameter(spectrumKey, psParameter);
                probaScore = probaScore * psParameter.getPsmProbability();
                fraction = Spectrum.getSpectrumFile(spectrumKey);
                if (!fractionScores.containsKey(fraction)) {
                    fractionScores.put(fraction, 1.0);
                }
                fractionScores.put(fraction, fractionScores.get(fraction) * psParameter.getPsmProbability());
            }
            psParameter = new PSParameter();
            psParameter.setPeptideProbabilityScore(probaScore);
            psParameter.setSecificMapKey(peptideMap.getKey(peptideMatch));
            for (String fractionName : fractionScores.keySet()) {
                psParameter.setFractionScore(fractionName, fractionScores.get(fractionName));
            }
            identification.addMatchParameter(peptideKey, psParameter);
            peptideMap.addPoint(probaScore, peptideMatch);

            waitingHandler.increaseSecondaryProgressValue();
            if (waitingHandler.isRunCanceled()) {
                return;
            }
        }
        metrics.setFoundModifications(foundModifications);

        waitingHandler.setSecondaryProgressDialogIntermediate(true);
    }

    /**
     * Attaches the peptide posterior error probabilities to the peptide
     * matches.
     *
     * @param waitingHandler
     */
    private void attachPeptideProbabilities(WaitingHandler waitingHandler) {

        waitingHandler.setTitle("Attaching Peptide Probabilities. Please Wait...");

        Identification identification = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber).getIdentification(IdentificationMethod.MS2_IDENTIFICATION);
        PSParameter psParameter = new PSParameter();

        waitingHandler.setSecondaryProgressDialogIntermediate(false);
        waitingHandler.setMaxSecondaryProgressValue(identification.getPeptideIdentification().size());

        for (String peptideKey : identification.getPeptideIdentification()) {
            psParameter = (PSParameter) identification.getMatchParameter(peptideKey, psParameter);
            psParameter.setPeptideProbability(peptideMap.getProbability(psParameter.getSecificMapKey(), psParameter.getPeptideProbabilityScore()));
            for (String fraction : psParameter.getFractions()) {
                psParameter.setFractionPEP(fraction, peptideMap.getProbability(psParameter.getSecificMapKey(), psParameter.getFractionScore(fraction)));
            }

            waitingHandler.increaseSecondaryProgressValue();
            if (waitingHandler.isRunCanceled()) {
                return;
            }
        }

        waitingHandler.setSecondaryProgressDialogIntermediate(true);
    }

    /**
     * Fills the protein map.
     *
     * @param waitingHandler the handler displaying feedback to the user
     */
    private void fillProteinMap(WaitingHandler waitingHandler) throws Exception {

        waitingHandler.setTitle("Filling Protein Map. Please Wait...");

        Identification identification = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber).getIdentification(IdentificationMethod.MS2_IDENTIFICATION);
        double probaScore;
        PSParameter psParameter = new PSParameter();
        ProteinMatch proteinMatch;

        int max = identification.getProteinIdentification().size();

        waitingHandler.setSecondaryProgressDialogIntermediate(false);
        waitingHandler.setMaxSecondaryProgressValue(max);

        HashMap<String, Double> fractionScores;

        for (String proteinKey : identification.getProteinIdentification()) {

            waitingHandler.increaseSecondaryProgressValue();
            if (waitingHandler.isRunCanceled()) {
                return;
            }

            fractionScores = new HashMap<String, Double>();

            probaScore = 1;
            proteinMatch = identification.getProteinMatch(proteinKey);
            for (String peptideKey : proteinMatch.getPeptideMatches()) {
                psParameter = (PSParameter) identification.getMatchParameter(peptideKey, psParameter);
                probaScore = probaScore * psParameter.getPeptideProbability();
                for (String fraction : psParameter.getFractions()) {
                    if (!fractionScores.containsKey(fraction)) {
                        fractionScores.put(fraction, 1.0);
                    }
                    fractionScores.put(fraction, fractionScores.get(fraction) * psParameter.getFractionPEP(fraction));
                }
            }
            psParameter = new PSParameter();
            psParameter.setProteinProbabilityScore(probaScore);
            for (String fractionName : fractionScores.keySet()) {
                psParameter.setFractionScore(fractionName, fractionScores.get(fractionName));
            }
            identification.addMatchParameter(proteinKey, psParameter);
            proteinMap.addPoint(probaScore, proteinMatch.isDecoy());
        }

        waitingHandler.setSecondaryProgressDialogIntermediate(true);
    }

    /**
     * Attaches the protein posterior error probability to the protein matches.
     *
     * @param waitingHandler the handler displaying feedback to the user
     */
    private void attachProteinProbabilities(WaitingHandler waitingHandler) {

        waitingHandler.setTitle("Attaching Protein Probabilities. Please Wait...");

        Identification identification = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber).getIdentification(IdentificationMethod.MS2_IDENTIFICATION);

        waitingHandler.setSecondaryProgressDialogIntermediate(false);
        waitingHandler.setMaxSecondaryProgressValue(identification.getProteinIdentification().size());

        PSParameter psParameter = new PSParameter();
        double proteinProbability;
        for (String proteinKey : identification.getProteinIdentification()) {
            psParameter = (PSParameter) identification.getMatchParameter(proteinKey, psParameter);
            proteinProbability = proteinMap.getProbability(psParameter.getProteinProbabilityScore());
            psParameter.setProteinProbability(proteinProbability);
            for (String fraction : psParameter.getFractions()) {
                psParameter.setFractionPEP(fraction, proteinMap.getProbability(psParameter.getFractionScore(fraction)));
            }

            waitingHandler.increaseSecondaryProgressValue();
            if (waitingHandler.isRunCanceled()) {
                return;
            }
        }

        waitingHandler.setSecondaryProgressDialogIntermediate(true);
    }

    /**
     * Solves protein inference issues when possible.
     *
     * @throws Exception exception thrown whenever it is attempted to attach two
     * different spectrum matches to the same spectrum from the same search
     * engine.
     *
     * @param waitingHandler the handler displaying feedback to the user
     * @throws IOException
     * @throws IllegalArgumentException
     */
    private void cleanProteinGroups(WaitingHandler waitingHandler) throws IOException, IllegalArgumentException {

        waitingHandler.setTitle("Cleaning Protein Groups. Please Wait...");

        Identification identification = experiment.getAnalysisSet(sample).getProteomicAnalysis(replicateNumber).getIdentification(IdentificationMethod.MS2_IDENTIFICATION);
        PSParameter psParameter = new PSParameter();
        boolean better;
        ProteinMatch proteinShared, proteinUnique;
        double sharedProteinProbabilityScore, uniqueProteinProbabilityScore;
        ArrayList<String> toRemove = new ArrayList<String>();

        int max = 3 * identification.getProteinIdentification().size();

        waitingHandler.setSecondaryProgressDialogIntermediate(false);
        waitingHandler.setMaxSecondaryProgressValue(max);

        for (String proteinSharedKey : identification.getProteinIdentification()) {

            if (ProteinMatch.getNProteins(proteinSharedKey) > 1) {
                psParameter = (PSParameter) identification.getMatchParameter(proteinSharedKey, psParameter);
                sharedProteinProbabilityScore = psParameter.getProteinProbabilityScore();
                if (sharedProteinProbabilityScore < 1) {
                    better = false;
                    for (String proteinUniqueKey : identification.getProteinIdentification()) {
                        if (ProteinMatch.contains(proteinSharedKey, proteinUniqueKey)) {
                            psParameter = (PSParameter) identification.getMatchParameter(proteinUniqueKey, psParameter);
                            uniqueProteinProbabilityScore = psParameter.getProteinProbabilityScore();
                            proteinUnique = identification.getProteinMatch(proteinUniqueKey);
                            proteinShared = identification.getProteinMatch(proteinSharedKey);
                            for (String sharedPeptideKey : proteinShared.getPeptideMatches()) {
                                proteinUnique.addPeptideMatch(sharedPeptideKey);
                            }
                            identification.setMatchChanged(proteinUnique);
                            if (uniqueProteinProbabilityScore <= sharedProteinProbabilityScore) {
                                better = true;
                            }
                        }
                    }
                    if (better) {
                        toRemove.add(proteinSharedKey);
                    } else {
                        waitingHandler.increaseSecondaryProgressValue();
                        if (waitingHandler.isRunCanceled()) {
                            return;
                        }
                    }
                }
            }
        }

        for (String proteinKey : toRemove) {
            psParameter = (PSParameter) identification.getMatchParameter(proteinKey, psParameter);
            proteinMap.removePoint(psParameter.getProteinProbabilityScore(), ProteinMatch.isDecoy(proteinKey));
            identification.removeMatch(proteinKey);
            waitingHandler.increaseSecondaryProgressValue();
        }

        int nSolved = toRemove.size();
        int nGroups = 0;
        int nLeft = 0;
        ArrayList<String> primaryDescription, secondaryDescription, accessions;

        // As we go through all protein ids, keep the sorted list of proteins and maxima in the instance of the Metrics class to pass them to the GUI afterwards
        // proteins are sorted according to the protein score, then number of peptides (inverted), then number of spectra (inverted).
        HashMap<Double, HashMap<Integer, HashMap<Integer, ArrayList<String>>>> orderMap =
                new HashMap<Double, HashMap<Integer, HashMap<Integer, ArrayList<String>>>>();
        ArrayList<Double> scores = new ArrayList<Double>();
        PSParameter probabilities = new PSParameter();
        ProteinMatch proteinMatch;
        PeptideMatch peptideMatch;
        double score;
        int nPeptides, nSpectra;
        double mw, maxMW = 0;
        Protein currentProtein = null;

        for (String proteinKey : identification.getProteinIdentification()) {
            proteinMatch = identification.getProteinMatch(proteinKey);

            if (!SequenceFactory.isDecoy(proteinKey)) {
                probabilities = (PSParameter) identification.getMatchParameter(proteinKey, probabilities);
                score = probabilities.getProteinProbabilityScore();
                nPeptides = -proteinMatch.getPeptideMatches().size();
                nSpectra = 0;


                try {
                    currentProtein = sequenceFactory.getProtein(proteinMatch.getMainMatch());
                } catch (Exception e) {
                    waitingHandler.appendReport("Protein not found: " + proteinMatch.getMainMatch() + "."); // This error is likely to be caught at an earlier stage
                }

                if (currentProtein != null) {
                    mw = currentProtein.computeMolecularWeight() / 1000;
                    if (mw > maxMW) {
                        maxMW = mw;
                    }
                }

                for (String peptideKey : proteinMatch.getPeptideMatches()) {
                    peptideMatch = identification.getPeptideMatch(peptideKey);
                    nSpectra -= peptideMatch.getSpectrumCount();
                }
                if (!orderMap.containsKey(score)) {
                    orderMap.put(score, new HashMap<Integer, HashMap<Integer, ArrayList<String>>>());
                    scores.add(score);
                }

                if (!orderMap.get(score).containsKey(nPeptides)) {
                    orderMap.get(score).put(nPeptides, new HashMap<Integer, ArrayList<String>>());
                }

                if (!orderMap.get(score).get(nPeptides).containsKey(nSpectra)) {
                    orderMap.get(score).get(nPeptides).put(nSpectra, new ArrayList<String>());
                }
                orderMap.get(score).get(nPeptides).get(nSpectra).add(proteinKey);
            }

            accessions = new ArrayList<String>(Arrays.asList(ProteinMatch.getAccessions(proteinKey)));
            Collections.sort(accessions);
            String mainKey = accessions.get(0);

            if (accessions.size() > 1) {
                boolean similarityFound = false;
                boolean allSimilar = false;
                psParameter = (PSParameter) identification.getMatchParameter(proteinKey, psParameter);
                for (int i = 0; i < accessions.size() - 1; i++) {
                    primaryDescription = parseDescription(accessions.get(i));
                    for (int j = i + 1; j < accessions.size(); j++) {
                        secondaryDescription = parseDescription(accessions.get(j));
                        if (getSimilarity(primaryDescription, secondaryDescription)) {
                            similarityFound = true;
                            mainKey = accessions.get(i);
                            break;
                        }
                    }
                    if (similarityFound) {
                        break;
                    }
                }
                if (similarityFound) {
                    allSimilar = true;
                    for (String key : accessions) {
                        if (!mainKey.equals(key)) {
                            primaryDescription = parseDescription(mainKey);
                            secondaryDescription = parseDescription(key);
                            if (!getSimilarity(primaryDescription, secondaryDescription)) {
                                allSimilar = false;
                                break;
                            }
                        }
                    }
                }
                if (!similarityFound) {
                    psParameter.setGroupClass(PSParameter.UNRELATED);
                    nGroups++;
                    nLeft++;

                    for (String peptideKey : proteinMatch.getPeptideMatches()) {
                        psParameter = (PSParameter) identification.getMatchParameter(peptideKey, psParameter);
                        psParameter.setGroupClass(PSParameter.UNRELATED);
                    }

                } else if (!allSimilar) {
                    psParameter.setGroupClass(PSParameter.ISOFORMS_UNRELATED);
                    nGroups++;
                    nSolved++;

                    for (String peptideKey : proteinMatch.getPeptideMatches()) {
                        psParameter = (PSParameter) identification.getMatchParameter(peptideKey, psParameter);
                        psParameter.setGroupClass(PSParameter.ISOFORMS_UNRELATED);
                    }

                } else {
                    psParameter.setGroupClass(PSParameter.ISOFORMS);
                    nGroups++;
                    nSolved++;

                    String mainMatch = proteinMatch.getMainMatch();
                    for (String peptideKey : proteinMatch.getPeptideMatches()) {
                        psParameter = (PSParameter) identification.getMatchParameter(peptideKey, psParameter);
                        peptideMatch = identification.getPeptideMatch(peptideKey);
                        boolean unrelated = false;
                        for (String protein : peptideMatch.getTheoreticPeptide().getParentProteins()) {
                            if (!proteinKey.contains(protein)) {
                                primaryDescription = parseDescription(mainMatch);
                                secondaryDescription = parseDescription(protein);
                                if (!getSimilarity(primaryDescription, secondaryDescription)) {
                                    unrelated = true;
                                    break;
                                }
                            }
                        }
                        if (unrelated) {
                            psParameter.setGroupClass(PSParameter.ISOFORMS_UNRELATED);
                        } else {
                            psParameter.setGroupClass(PSParameter.ISOFORMS);
                        }
                    }
                }
            } else {
                String mainMatch = proteinMatch.getMainMatch();
                for (String peptideKey : proteinMatch.getPeptideMatches()) {
                    psParameter = (PSParameter) identification.getMatchParameter(peptideKey, psParameter);
                    peptideMatch = identification.getPeptideMatch(peptideKey);
                    boolean unrelated = false;
                    boolean otherProtein = false;
                    for (String protein : peptideMatch.getTheoreticPeptide().getParentProteins()) {
                        if (!proteinKey.contains(protein)) {
                            otherProtein = true;
                            primaryDescription = parseDescription(mainMatch);
                            secondaryDescription = parseDescription(protein);
                            if (primaryDescription == null || secondaryDescription == null || !getSimilarity(primaryDescription, secondaryDescription)) {
                                unrelated = true;
                                break;
                            }
                        }
                    }
                    if (otherProtein) {
                        psParameter.setGroupClass(PSParameter.ISOFORMS);
                    }
                    if (unrelated) {
                        psParameter.setGroupClass(PSParameter.UNRELATED);
                    }
                }
            }
            if (ProteinMatch.getNProteins(proteinKey) > 1) {
                if (!proteinMatch.getMainMatch().equals(mainKey)) {
                    proteinMatch.setMainMatch(mainKey);
                    identification.setMatchChanged(proteinMatch);
                }
            }

            waitingHandler.increaseSecondaryProgressValue();
            if (waitingHandler.isRunCanceled()) {
                return;
            }
        }

        ArrayList<String> proteinList = new ArrayList<String>();
        ArrayList<Double> scoreList = new ArrayList<Double>(orderMap.keySet());
        Collections.sort(scoreList);
        ArrayList<Integer> nPeptideList, nPsmList;
        ArrayList<String> tempList;
        int maxPeptides = 0;
        int maxSpectra = 0;

        for (double currentScore : scoreList) {
            nPeptideList = new ArrayList<Integer>(orderMap.get(currentScore).keySet());
            Collections.sort(nPeptideList);
            if (nPeptideList.get(0) < maxPeptides) {
                maxPeptides = nPeptideList.get(0);
            }
            for (int currentNPeptides : nPeptideList) {
                nPsmList = new ArrayList<Integer>(orderMap.get(currentScore).get(currentNPeptides).keySet());
                Collections.sort(nPsmList);
                if (nPsmList.get(0) < maxSpectra) {
                    maxSpectra = nPsmList.get(0);
                }
                for (int currentNPsms : nPsmList) {
                    tempList = orderMap.get(currentScore).get(currentNPeptides).get(currentNPsms);
                    Collections.sort(tempList);
                    proteinList.addAll(tempList);

                    waitingHandler.increaseSecondaryProgressValue(tempList.size());
                    if (waitingHandler.isRunCanceled()) {
                        return;
                    }
                }
            }
        }

        metrics.setProteinKeys(proteinList);
        metrics.setMaxNPeptides(-maxPeptides);
        metrics.setMaxNSpectra(-maxSpectra);
        metrics.setMaxMW(maxMW);

        waitingHandler.setSecondaryProgressDialogIntermediate(true);
        waitingHandler.appendReport(nSolved + " conflicts resolved. " + nGroups + " protein groups remaining (" + nLeft + " suspicious).");
    }

    /**
     * Parses a protein description retaining only words longer than 3
     * characters.
     *
     * @param proteinAccession the accession of the inspected protein
     * @return description words longer than 3 characters
     */
    private ArrayList<String> parseDescription(String proteinAccession) throws IOException, IllegalArgumentException {
        String description = sequenceFactory.getHeader(proteinAccession).getDescription();

        if (description == null) {
            return new ArrayList<String>();
        }

        ArrayList<String> result = new ArrayList<String>();
        for (String component : description.split(" ")) {
            if (component.length() > 3) {
                result.add(component);
            }
        }
        return result;
    }

    /**
     * Simplistic method comparing protein descriptions. Returns true if both
     * descriptions are of same length and present more than half similar words.
     *
     * @param primaryDescription The parsed description of the first protein
     * @param secondaryDescription The parsed description of the second protein
     * @return a boolean indicating whether the descriptions are similar
     */
    private boolean getSimilarity(ArrayList<String> primaryDescription, ArrayList<String> secondaryDescription) {
        if (primaryDescription.size() == secondaryDescription.size()) {
            int nMatch = 0;
            for (int i = 0; i < primaryDescription.size(); i++) {
                if (primaryDescription.get(i).equals(secondaryDescription.get(i))) {
                    nMatch++;
                }
            }
            if (nMatch >= primaryDescription.size() / 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the metrics picked-up while loading the files.
     *
     * @return the metrics picked-up while loading the files
     */
    public Metrics getMetrics() {
        return metrics;
    }

    /**
     * Sets the protein count map
     *
     * @param proteinCount the protein count map
     */
    public void setProteinCountMap(HashMap<String, Integer> proteinCount) {
        this.proteinCount = proteinCount;
    }

    /**
     * Replaces the needed PTMs by PeptideShaker PTMs in the factory.
     *
     * @param searchParameters the search parameters containing the modification
     * profile to use
     */
    public static void setPeptideShakerPTMs(SearchParameters searchParameters) {
        ArrayList<String> residues, utilitiesNames;
        PTMFactory ptmFactory = PTMFactory.getInstance();
        ArrayList<NeutralLoss> neutralLosses = new ArrayList<NeutralLoss>();
        ArrayList<ReporterIon> reporterIons = new ArrayList<ReporterIon>();

        for (String peptideShakerName : searchParameters.getModificationProfile().getPeptideShakerNames()) {
            residues = new ArrayList<String>();
            utilitiesNames = new ArrayList<String>();
            int modType = -1;
            double mass = -1;
            for (String utilitiesName : searchParameters.getModificationProfile().getUtilitiesNames()) {
                if (peptideShakerName.equals(searchParameters.getModificationProfile().getPeptideShakerName(utilitiesName))) {
                    neutralLosses = new ArrayList<NeutralLoss>();
                    reporterIons = new ArrayList<ReporterIon>();
                    PTM sePtm = ptmFactory.getPTM(utilitiesName);
                    for (String aa : sePtm.getResidues()) {
                        if (!residues.contains(aa)) {
                            residues.add(aa);
                        }
                    }
                    if (modType == -1) {
                        modType = sePtm.getType();
                    } else if (sePtm.getType() != modType) {
                        modType = PTM.MODAA; // case difficult to handle so use the default AA option
                    }
                    mass = sePtm.getMass();
                    utilitiesNames.add(utilitiesName);
                    boolean found;
                    for (NeutralLoss neutralLoss : sePtm.getNeutralLosses()) {
                        found = false;
                        for (NeutralLoss alreadyImplemented : neutralLosses) {
                            if (neutralLoss.isSameAs(alreadyImplemented)) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            neutralLosses.add(neutralLoss);
                        }
                    }
                    for (ReporterIon reporterIon : sePtm.getReporterIons()) {
                        found = false;
                        for (ReporterIon alreadyImplemented : reporterIons) {
                            if (reporterIon.isSameAs(alreadyImplemented)) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            reporterIons.add(reporterIon);
                        }
                    }
                }
            }
            for (String utilitiesName : utilitiesNames) {
                PTM newPTM = new PTM(modType, peptideShakerName, searchParameters.getModificationProfile().getShortName(peptideShakerName), mass, residues);
                newPTM.setNeutralLosses(neutralLosses);
                newPTM.setReporterIons(reporterIons);
                ptmFactory.replacePTM(utilitiesName, newPTM);
            }
        }
    }
}
