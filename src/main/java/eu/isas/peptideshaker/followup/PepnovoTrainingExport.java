/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package eu.isas.peptideshaker.followup;

import com.compomics.util.experiment.identification.Identification;
import com.compomics.util.experiment.identification.matches.SpectrumMatch;
import com.compomics.util.experiment.massspectrometry.MSnSpectrum;
import com.compomics.util.experiment.massspectrometry.Spectrum;
import com.compomics.util.experiment.massspectrometry.SpectrumFactory;
import com.compomics.util.gui.waiting.WaitingHandler;
import com.compomics.util.gui.waiting.waitinghandlers.WaitingDialog;
import com.compomics.util.preferences.AnnotationPreferences;
import static eu.isas.peptideshaker.followup.RecalibrationExporter.getRecalibratedFileName;
import eu.isas.peptideshaker.myparameters.PSParameter;
import eu.isas.peptideshaker.recalibration.RunMzDeviation;
import eu.isas.peptideshaker.recalibration.SpectrumRecalibrator;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import uk.ac.ebi.jmzml.xml.io.MzMLUnmarshallerException;

/**
 * This class exports de novo training files
 *
 * @author Marc
 */
public class PepnovoTrainingExport {

    /**
     * Suffix for the mgf file containing annotated spectra making the "good training" set
     */
    public static final String goodTraining = "good_training";
    /**
     * Suffix for the mgf file containing spectra making the "bad training" set.
     */
    public static final String badTraining = "bad_training";
    
    
    /**
     * Exports the pepnovo training files, eventually recalibrated with the
     * recalibrated mgf.
     *
     * @param destinationFolder the folder where to write the output files
     * @param identification the identification
     * @param annotationPreferences the annotation preferences. Only necessary
     * in recalibration mode.
     * @param confidenceLevel the confidence threshold to use for the export in percent. PSMs above this threshold (threshold inclusive) will be used for the good training set. PSMs below 1-threshold (1-threshold inclusive) will be used for the bad training set.
     * @param recalibrate boolean indicating whether the files shall be
     * recalibrated
     * @param waitingHandler waiting handler displaying progress to the user and
     * allowing canceling the process
     */
    public static void exportPepnovoTrainingFiles(File destinationFolder, Identification identification, AnnotationPreferences annotationPreferences, double confidenceLevel, boolean recalibrate, WaitingHandler waitingHandler) throws IOException, MzMLUnmarshallerException, SQLException, ClassNotFoundException, InterruptedException {


        SpectrumFactory spectrumFactory = SpectrumFactory.getInstance();
        SpectrumRecalibrator spectrumRecalibrator = new SpectrumRecalibrator();

        int progress = 1;

        for (String fileName : spectrumFactory.getMgfFileNames()) {

            if (recalibrate) {
                if (waitingHandler != null) {
                    if (waitingHandler.isRunCanceled()) {
                        break;
                    }

                    waitingHandler.setWaitingText("Recalibrating " + fileName + " (" + progress + "/" + spectrumFactory.getMgfFileNames().size() + ").");
                    waitingHandler.setSecondaryProgressValue(0);
                    waitingHandler.setSecondaryProgressDialogIndeterminate(false);
                    waitingHandler.setMaxSecondaryProgressValue(2 * spectrumFactory.getNSpectra(fileName));
                }

                spectrumRecalibrator.estimateErrors(fileName, identification, annotationPreferences, waitingHandler);

            }
            
            PSParameter psParameter = new PSParameter();
            identification.loadSpectrumMatchParameters(fileName, psParameter, waitingHandler);
            
            if (waitingHandler != null) {
                if (waitingHandler.isRunCanceled()) {
                    return;
                }
                waitingHandler.setWaitingText("Selecting good PSMs " + fileName + " (" + progress + "/"
                        + spectrumFactory.getMgfFileNames().size() + ").");
            }
            ArrayList<String> keys = new ArrayList<String>();
            for (String spectrumTitle : spectrumFactory.getSpectrumTitles(fileName)) {

                String spectrumKey = Spectrum.getSpectrumKey(fileName, spectrumTitle);
                psParameter = (PSParameter) identification.getSpectrumMatchParameter(spectrumKey, psParameter);
                if (psParameter.getPsmConfidence() >= confidenceLevel) {
                    keys.add(spectrumKey);
                }
                if (waitingHandler != null) {
                    if (waitingHandler.isRunCanceled()) {
                        return;
                    }
                    waitingHandler.increaseSecondaryProgressValue();
                }
            }
            identification.loadSpectrumMatches(keys, waitingHandler);
            
            if (waitingHandler != null) {
                if (waitingHandler.isRunCanceled()) {
                    return;
                }
                waitingHandler.setWaitingText("Exporting Pepnovo training files " + fileName + " (" + progress + "/"
                        + spectrumFactory.getMgfFileNames().size() + ").");
            }
            
            File file = new File(destinationFolder, getRecalibratedFileName(fileName));
            BufferedWriter writerRecalibration = new BufferedWriter(new FileWriter(file));
            file = new File(destinationFolder, getRecalibratedFileName(fileName));
            BufferedWriter writerGood = new BufferedWriter(new FileWriter(file));
            file = new File(destinationFolder, getRecalibratedFileName(fileName));
            BufferedWriter writerBad = new BufferedWriter(new FileWriter(file));
            
             try {
                 
            for (String spectrumTitle : spectrumFactory.getSpectrumTitles(fileName)) {

                String spectrumKey = Spectrum.getSpectrumKey(fileName, spectrumTitle);

                MSnSpectrum spectrum;
                if (recalibrate) {
                spectrum = spectrumRecalibrator.recalibrateSpectrum(fileName, spectrumTitle, true, true);
                spectrum.writeMgf(writerRecalibration);
                } else {
                    spectrum = (MSnSpectrum) spectrumFactory.getSpectrum(spectrumKey);
                }
                
                psParameter = (PSParameter) identification.getSpectrumMatchParameter(spectrumKey, psParameter);
                if (psParameter.getPsmConfidence() >= confidenceLevel) {
                    SpectrumMatch spectrumMatch = identification.getSpectrumMatch(spectrumKey);
                    String sequence = spectrumMatch.getBestAssumption().getPeptide().getSequence();
                    HashMap<String, String> tags = new HashMap<String, String>();
                    tags.put("SEQ", sequence);
                    spectrum.writeMgf(writerGood, tags);
                }
                if (psParameter.getPsmConfidence()<= 100 - confidenceLevel) {
                    spectrum.writeMgf(writerBad);
                }

                if (waitingHandler != null) {
                if (waitingHandler.isRunCanceled()) {
                    return;
                }
                    waitingHandler.increaseProgressValue();
                }
            }
             } finally {
                 writerRecalibration.close();
                 writerBad.close();
                 writerGood.close();
             }

            spectrumRecalibrator.clearErrors(fileName);
        }
    }

    /**
     * Returns the name of the recalibrated file.
     *
     * @param fileName the original file name
     * @return the name of the recalibrated file
     */
    public static String getRecalibratedFileName(String fileName) {
        String tempName = fileName.substring(0, fileName.lastIndexOf("."));
        String extension = fileName.substring(fileName.lastIndexOf("."));
        return tempName + "_recalibrated" + extension;
    }

    /**
     * Returns the name of the good training set file.
     *
     * @param fileName the original file name
     * @return the name of the good training set file
     */
    public static String getGoodSetFileName(String fileName) {
        String tempName = fileName.substring(0, fileName.lastIndexOf("."));
        String extension = fileName.substring(fileName.lastIndexOf("."));
        return tempName + goodTraining + extension;
    }

    /**
     * Returns the name of the bad training set file.
     *
     * @param fileName the original file name
     * @return the name of the bad training set file
     */
    public static String getBadetFileName(String fileName) {
        String tempName = fileName.substring(0, fileName.lastIndexOf("."));
        String extension = fileName.substring(fileName.lastIndexOf("."));
        return tempName + badTraining + extension;
    }
    
}