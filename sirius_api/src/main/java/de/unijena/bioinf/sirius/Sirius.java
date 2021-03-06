/*
 *  This file is part of the SIRIUS library for analyzing MS and MS/MS data
 *
 *  Copyright (C) 2013-2015 Kai DÃ¼hrkop
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with SIRIUS.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.unijena.bioinf.sirius;

import com.google.common.collect.Iterables;
import de.unijena.bioinf.ChemistryBase.chem.*;
import de.unijena.bioinf.ChemistryBase.chem.utils.biotransformation.BioTransformation;
import de.unijena.bioinf.ChemistryBase.chem.utils.biotransformation.BioTransformer;
import de.unijena.bioinf.ChemistryBase.chem.utils.scoring.SupportVectorMolecularFormulaScorer;
import de.unijena.bioinf.ChemistryBase.jobs.SiriusJobs;
import de.unijena.bioinf.ChemistryBase.ms.*;
import de.unijena.bioinf.ChemistryBase.ms.ft.*;
import de.unijena.bioinf.ChemistryBase.ms.utils.SimpleMutableSpectrum;
import de.unijena.bioinf.ChemistryBase.ms.utils.SimpleSpectrum;
import de.unijena.bioinf.ChemistryBase.ms.utils.Spectrums;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.AbstractTreeComputationInstance;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.FasterTreeComputationInstance;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.FragmentationPatternAnalysis;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.TreeComputationInstance;
import de.unijena.bioinf.FragmentationTreeConstruction.model.*;
import de.unijena.bioinf.IsotopePatternAnalysis.IsotopePattern;
import de.unijena.bioinf.IsotopePatternAnalysis.IsotopePatternAnalysis;
import de.unijena.bioinf.IsotopePatternAnalysis.generation.IsotopePatternGenerator;
import de.unijena.bioinf.IsotopePatternAnalysis.prediction.DNNRegressionPredictor;
import de.unijena.bioinf.IsotopePatternAnalysis.prediction.ElementPredictor;
import de.unijena.bioinf.babelms.CloseableIterator;
import de.unijena.bioinf.babelms.MsExperimentParser;
import de.unijena.bioinf.jjobs.BasicJJob;
import de.unijena.bioinf.jjobs.BasicMasterJJob;
import de.unijena.bioinf.jjobs.JobProgressEvent;
import de.unijena.bioinf.jjobs.MasterJJob;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;


//todo we should cleanup the api methods, proof which should be private and which are no longer needed, or at least change them, so that they use the identification job
public class Sirius {

    private static final double MAX_TREESIZE_INCREASE = 3d;
    private static final double TREE_SIZE_INCREASE = 1d;
    private static final int MIN_NUMBER_OF_EXPLAINED_PEAKS = 15;
    private static final double MIN_EXPLAINED_INTENSITY = 0.7d;
    private static final int MIN_NUMBER_OF_TREES_CHECK_FOR_INTENSITY = 5;

    private static final double MINIMAL_SCORE_FOR_APPLY_FILTER = 10d;
    private static final double ISOTOPE_SCORE_FILTER_THRESHOLD = 2.5d;

    protected Profile profile;
    protected ElementPredictor elementPrediction;
    protected Progress progress;
    protected PeriodicTable table;
    protected boolean autoIonMode;
//    protected JobManager jobManager;


    public static void main(String[] args) {
        final Sirius sirius = new Sirius();

        try {
            Ms2Experiment experiment = sirius.parseExperiment(new File("/home/kaidu/data/ms/demo-data/ms/bicculine_ms1only.ms")).next();

            System.out.println(sirius.identify(experiment).getRawJSONTree());

        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public class SiriusIdentificationJob extends BasicMasterJJob<List<IdentificationResult>> {
        private final Ms2Experiment experiment;
        private final int numberOfResultsToKeep;
        private final boolean beautifyTrees;

        public SiriusIdentificationJob(Ms2Experiment experiment, int numberOfResultsToKeep, boolean beautifyTrees) {
            super(JobType.CPU);
            this.experiment = experiment;
            this.numberOfResultsToKeep = numberOfResultsToKeep;
            this.beautifyTrees = beautifyTrees;
        }

        @Override
        protected List<IdentificationResult> compute() throws Exception {
            final AbstractTreeComputationInstance instance = getTreeComputationImplementation(getMs2Analyzer(), experiment, numberOfResultsToKeep);
            instance.addPropertyChangeListener(JobProgressEvent.JOB_PROGRESS_EVENT, evt -> updateProgress(0, 105, (int) evt.getNewValue()));
            final ProcessedInput pinput = instance.validateInput();
            performMs1Analysis(instance);
            submitSubJob(instance);
            AbstractTreeComputationInstance.FinalResult fr = instance.awaitResult();

            List<IdentificationResult> r = createIdentificationResults(fr, instance);//postprocess results
            return r;
        }

        private List<IdentificationResult> createIdentificationResults(AbstractTreeComputationInstance.FinalResult fr, AbstractTreeComputationInstance computationInstance) {
            addScoreThresholdOnUnconsideredCandidates(fr, computationInstance.precompute());

            final List<IdentificationResult> irs = new ArrayList<>();
            int k = 0;
            for (FTree tree : fr.getResults()) {
                IdentificationResult result = new IdentificationResult(tree, ++k);
                irs.add(result);

                //beautify tree (try to explain more peaks)
                if (beautifyTrees)
                    beautifyTree(this, result, experiment, experiment.getAnnotation(ForbidRecalibration.class, ForbidRecalibration.ALLOWED) == ForbidRecalibration.ALLOWED);
                final ProcessedInput processedInput = result.getStandardTree().getAnnotationOrNull(ProcessedInput.class);
                if (processedInput != null)
                    result.setAnnotation(Ms2Experiment.class, processedInput.getExperimentInformation());
                else result.setAnnotation(Ms2Experiment.class, experiment);
            }
            return irs;
        }


        public Ms2Experiment getExperiment() {
            return experiment;
        }

        public int getNumberOfResultsToKeep() {
            return numberOfResultsToKeep;
        }
    }


    //public final static String ISOTOPE_SCORE = "isotope";

    public Sirius(String profileName) throws IOException {
        profile = new Profile(profileName);
        loadMeasurementProfile();
        this.progress = new Progress.Quiet();
    }

    public Sirius() {
        try {
            profile = new Profile("default");
            loadMeasurementProfile();
            this.progress = new Progress.Quiet();
        } catch (IOException e) { // should be in classpath
            throw new RuntimeException(e);
        }
    }

    public Sirius.SiriusIdentificationJob makeIdentificationJob(final Ms2Experiment experiment, final int numberOfResultsToReport) {
        return makeIdentificationJob(experiment, numberOfResultsToReport, true);
    }

    public Sirius.SiriusIdentificationJob makeIdentificationJob(final Ms2Experiment experiment, final int numberOfResultsToReport, final boolean beautifyTrees) {
        return new SiriusIdentificationJob(experiment, numberOfResultsToReport, beautifyTrees);
    }

    /*public BasicJJob<IdentificationResult> makeTreeComputationJob(final Ms2Experiment experiment, final MolecularFormula formula) {
        return new BasicJJob<IdentificationResult>() {
            @Override
            protected IdentificationResult compute() throws Exception {
                return Sirius.this.compute(experiment, formula);
            }
        };
    }*/

    /**
     * set new constraints for the molecular formulas that should be considered by Sirius
     * constraints consist of a set of allowed elements together with upperbounds for this elements
     * You can set constraints as String with a format like "CHNOP[7]" where each bracket contains the upperbound
     * for the preceeding element. Elements without upperbound are unbounded.
     * <p>
     * The elemens CHNOPS will always be contained in the element set. However, you can change their upperbound which
     * is unbounded by default.
     *
     * @param newConstraints
     * Formula Constraits are now set per input instance via {@link #setFormulaConstraints(Ms2Experiment, FormulaConstraints)}
     */
    @Deprecated
    public void setFormulaConstraints(String newConstraints) {
        setFormulaConstraints(new FormulaConstraints(newConstraints));
    }

    /**
     * set new constraints for the molecular formulas that should be considered by Sirius
     * constraints consist of a set of allowed elements together with upperbounds for this elements
     * <p>
     * The elemens CHNOPS will always be contained in the element set. However, you can change their upperbound which
     * is unbounded by default.
     *
     * @param constraints
     * Formula Constraits are now set per input instance via {@link #setFormulaConstraints(Ms2Experiment, FormulaConstraints)}
     */
    @Deprecated
    public void setFormulaConstraints(FormulaConstraints constraints) {
        final PeriodicTable tb = PeriodicTable.getInstance();
        final Element[] chnop = new Element[]{tb.getByName("C"), tb.getByName("H"), tb.getByName("N"), tb.getByName("O"), tb.getByName("P")};
        final FormulaConstraints fc = constraints.getExtendedConstraints(chnop);
        getMs1Analyzer().getDefaultProfile().setFormulaConstraints(fc);
        getMs2Analyzer().getDefaultProfile().setFormulaConstraints(fc);
    }

    /**
     * parses a file and return an iterator over all MS/MS experiments contained in this file
     * An experiment consists of all MS and MS/MS spectra belonging to one feature (=compound).
     * <p>
     * Supported file formats are .ms and .mgf
     * <p>
     * The returned iterator supports the close method to close the input stream. The stream is closed automatically,
     * after the last element is iterated. However, it is recommendet to use the following syntax (since java 7):
     * <p>
     * <pre>
     * {@code
     * try ( CloseableIterator<Ms2Experiment> iter = sirius.parse(myfile) ) {
     *   while (iter.hasNext()) {
     *      Ms2Experiment experiment = iter.next();
     *      // ...
     *   }
     * }}
     * </pre>
     *
     * @param file
     * @return
     * @throws IOException
     */
    public CloseableIterator<Ms2Experiment> parseExperiment(File file) throws IOException {
        return new MsExperimentParser().getParser(file).parseFromFileIterator(file);
    }

    /**
     * Deprecated: Progress handling should be done via Job API
     */
    @Deprecated
    public Progress getProgress() {
        return progress;
    }

    /**
     * Deprecated: Progress handling should be done via Job API
     */
    @Deprecated
    public void setProgress(Progress progress) {
        this.progress = progress;
    }

    public FragmentationPatternAnalysis getMs2Analyzer() {
        return profile.fragmentationPatternAnalysis;
    }

    public IsotopePatternAnalysis getMs1Analyzer() {
        return profile.isotopePatternAnalysis;
    }

    private void loadMeasurementProfile() {
        this.table = PeriodicTable.getInstance();
        // make mutable
        profile.fragmentationPatternAnalysis.setDefaultProfile(new MutableMeasurementProfile(profile.fragmentationPatternAnalysis.getDefaultProfile()));
        profile.isotopePatternAnalysis.setDefaultProfile(new MutableMeasurementProfile(profile.isotopePatternAnalysis.getDefaultProfile()));
        this.elementPrediction = null;
        this.autoIonMode = false;
    }

    public ElementPredictor getElementPrediction() {
        if (elementPrediction == null) {
            /*
            DNNElementPredictor defaultPredictor = new DNNElementPredictor();
            defaultPredictor.setThreshold(0.05);
            defaultPredictor.setThreshold("S", 0.1);
            defaultPredictor.setThreshold("Si", 0.8);
            elementPrediction = defaultPredictor;
            */
            DNNRegressionPredictor defaultPredictor = new DNNRegressionPredictor();
            defaultPredictor.disableSilicon();
            elementPrediction = defaultPredictor;
        }
        return elementPrediction;
    }

    public void setElementPrediction(ElementPredictor elementPrediction) {
        this.elementPrediction = elementPrediction;
    }

    @Deprecated
    public boolean isAutoIonMode() {
        return autoIonMode;
    }

    @Deprecated
    public void setAutoIonMode(boolean autoIonMode) {
        this.autoIonMode = autoIonMode;
    }

    /**
     * for internal use to easily switch and experiment with implementation details
     */
    public static boolean USE_FAST_MODE = true;

    protected AbstractTreeComputationInstance getTreeComputationImplementation(FragmentationPatternAnalysis analyzer, Ms2Experiment input, int numberOfResultsToKeep) {
        if (USE_FAST_MODE)
            return new FasterTreeComputationInstance(analyzer, input, numberOfResultsToKeep);
        else
            return new TreeComputationInstance(analyzer, input, numberOfResultsToKeep);
    }

    /**
     * try to guess ionization from MS1. multiple  suggestions possible. In doubt [M]+ is ignored (cannot distinguish from isotope pattern)!
     *
     * @param experiment
     * @param candidateIonizations array of possible ionizations (lots of different adducts very likely make no sense!)
     * @return
     */
    public PrecursorIonType[] guessIonization(Ms2Experiment experiment, PrecursorIonType[] candidateIonizations) {
        Spectrum<Peak> spec = experiment.getMergedMs1Spectrum();
        SimpleMutableSpectrum mutableMerged = null;
        if (spec != null) {
            mutableMerged = new MutableMs2Spectrum(spec);
            Spectrums.filterIsotpePeaks(mutableMerged, new Deviation(100), 0.3, 0.75, 5, new ChemicalAlphabet());
        }
        //todo hack: if the merged spectrum only contains a single monoisotopic peak: use most intense MS1 (problem if only M+H+ and M+ in merged MS1?)
        if ((mutableMerged == null || mutableMerged.size() == 1) && experiment.getMs1Spectra().size() > 0) {
            spec = Spectrums.selectSpectrumWithMostIntensePrecursor(experiment.getMs1Spectra(), experiment.getIonMass(), getMs1Analyzer().getDefaultProfile().getAllowedMassDeviation());
            if (spec == null) spec = experiment.getMs1Spectra().get(0);
        }

        if (spec == null) return candidateIonizations;

        SimpleMutableSpectrum mutableSpectrum = new SimpleMutableSpectrum(spec);
        Spectrums.normalizeToMax(mutableSpectrum, 100d);
        Spectrums.applyBaseline(mutableSpectrum, 1d);

        PrecursorIonType[] ionType = Spectrums.guessIonization(mutableSpectrum, experiment.getIonMass(), profile.fragmentationPatternAnalysis.getDefaultProfile().getAllowedMassDeviation(), candidateIonizations);
        return ionType;
    }

    /**
     * Search for peaks in MS1 that indicate certain
     *
     * @param processedInput
     */
    @Deprecated
    public void detectPossibleAdductsFromMs1(ProcessedInput processedInput) {
        final PrecursorIonType[] adductTypes;
        if (processedInput.getExperimentInformation().getPrecursorIonType().isIonizationUnknown()) {
            adductTypes = guessIonization(processedInput.getExperimentInformation(), Iterables.toArray(PeriodicTable.getInstance().getKnownLikelyPrecursorIonizations(processedInput.getExperimentInformation().getPrecursorIonType().getCharge()), PrecursorIonType.class));
        } else {
            adductTypes = guessIonization(processedInput.getExperimentInformation(), PeriodicTable.getInstance().adductsByIonisation(processedInput.getExperimentInformation().getPrecursorIonType()).toArray(new PrecursorIonType[0]));
        }
        setAllowedAdducts(processedInput, adductTypes);
        final Set<Ionization> ionModes = new HashSet<>();
        for (PrecursorIonType ionType : adductTypes) ionModes.add(ionType.getIonization());
        setAllowedIonModes(processedInput, ionModes.toArray(new Ionization[ionModes.size()]));
    }

    public void detectPossibleIonModesFromMs1(ProcessedInput processedInput) {
        final List<PrecursorIonType> ionTypes = new ArrayList<>();
        for (Ionization ionMode : PeriodicTable.getInstance().getKnownIonModes(processedInput.getExperimentInformation().getPrecursorIonType().getCharge())) {
            ionTypes.add(PrecursorIonType.getPrecursorIonType(ionMode));
        }
        detectPossibleIonModesFromMs1(processedInput, ionTypes.toArray(new PrecursorIonType[ionTypes.size()]));
    }

    public void detectPossibleIonModesFromMs1(ProcessedInput processedInput, PrecursorIonType... allowedIonModes) {
        final PrecursorIonType[] ionModes = guessIonization(processedInput.getExperimentInformation(), allowedIonModes);
        final PossibleIonModes pim = processedInput.getAnnotation(PossibleIonModes.class, new PossibleIonModes());
        if (ionModes.length>0) pim.updateGuessedIons(ionModes);
        processedInput.setAnnotation(PossibleIonModes.class, pim);
        //also update PossibleAdducts
        final PossibleAdducts pa = processedInput.getAnnotation(PossibleAdducts.class, new PossibleAdducts());
        pa.update(pim);
    }

    /**
     * Identify the molecular formula of the measured compound using the provided MS and MSMS data
     *
     * @param uexperiment input data
     * @return the top tree
     */
    @Deprecated
    public IdentificationResult identify(Ms2Experiment uexperiment) {
        return identify(uexperiment, 1).get(0);
    }

    /**
     * Identify the molecular formula of the measured compound using the provided MS and MSMS data
     *
     * @param uexperiment        input data
     * @param numberOfCandidates number of top candidates to return
     * @return a list of identified molecular formulas together with their tree
     */
    @Deprecated
    public List<IdentificationResult> identify(Ms2Experiment uexperiment, int numberOfCandidates) {
        final AbstractTreeComputationInstance instance = getTreeComputationImplementation(getMs2Analyzer(), uexperiment, numberOfCandidates);
        final ProcessedInput pinput = instance.validateInput();
        performMs1Analysis(instance);
        SiriusJobs.getGlobalJobManager().submitJob(instance);
        AbstractTreeComputationInstance.FinalResult fr = instance.takeResult();
        final List<IdentificationResult> irs = createIdentificationResults(fr, instance);//postprocess results
        return irs;
    }

    @Deprecated
    public List<IdentificationResult> identifyPrecursorAndIonization(Ms2Experiment uexperiment, int numberOfCandidates, IsotopePatternHandling iso) {
        final MutableMs2Experiment exp = new MutableMs2Experiment(uexperiment);
        exp.setAnnotation(PossibleIonModes.class, PossibleIonModes.defaultFor(uexperiment.getPrecursorIonType().getCharge()));
        return identify(exp, numberOfCandidates, true, iso);
    }

    /**
     * Identify the molecular formula of the measured compound by combining an isotope pattern analysis on MS data with a fragmentation pattern analysis on MS/MS data
     *
     * @param uexperiment        input data
     * @param numberOfCandidates number of candidates to output
     * @param recalibrating      true if spectra should be recalibrated during tree computation
     * @param deisotope          set this to 'omit' to ignore isotope pattern, 'filter' to use it for selecting molecular formula candidates or 'score' to rerank the candidates according to their isotope pattern
     * @param whiteList          restrict the analysis to this subset of molecular formulas. If this set is empty, consider all possible molecular formulas
     * @return a list of identified molecular formulas together with their tree
     */
    @Deprecated
    public List<IdentificationResult> identify(Ms2Experiment uexperiment, int numberOfCandidates, boolean recalibrating, IsotopePatternHandling deisotope, Set<MolecularFormula> whiteList) {
        final AbstractTreeComputationInstance instance = getTreeComputationImplementation(getMs2Analyzer(), uexperiment, numberOfCandidates);
        final ProcessedInput pinput = instance.validateInput();
        pinput.setAnnotation(ForbidRecalibration.class, recalibrating ? ForbidRecalibration.ALLOWED : ForbidRecalibration.FORBIDDEN);
        if (whiteList != null) pinput.setAnnotation(Whiteset.class, new Whiteset(whiteList));
        performMs1Analysis(instance, deisotope);
        SiriusJobs.getGlobalJobManager().submitJob(instance);
        AbstractTreeComputationInstance.FinalResult fr = instance.takeResult();
        final List<IdentificationResult> irs = createIdentificationResults(fr, instance);//postprocess results
        return irs;
    }

    protected List<IdentificationResult> createIdentificationResults(AbstractTreeComputationInstance.FinalResult fr, AbstractTreeComputationInstance computationInstance) {
        addScoreThresholdOnUnconsideredCandidates(fr, computationInstance.precompute());

        final List<IdentificationResult> irs = new ArrayList<>();
        int k = 0;
        for (FTree tree : fr.getResults()) {
            IdentificationResult result = new IdentificationResult(tree, ++k);
            irs.add(result);

        }
        return irs;
    }

    private static void addScoreThresholdOnUnconsideredCandidates(AbstractTreeComputationInstance.FinalResult fr, ProcessedInput processedInput) {
        //add annotation of score bound on unconsidered instances
        int numberOfResults = fr.getResults().size();
        if (numberOfResults == 0) return;
        int numberOfDecompositions = processedInput.getAnnotationOrThrow(DecompositionList.class).getDecompositions().size();
        int numberOfUnconsideredCandidates = numberOfDecompositions - numberOfResults;
        //trees should be sorted by score
        double lowestConsideredCandidatesScore = fr.getResults().get(numberOfResults - 1).getAnnotationOrThrow(TreeScoring.class).getOverallScore();
        UnconsideredCandidatesUpperBound unconsideredCandidatesUpperBound = new UnconsideredCandidatesUpperBound(numberOfUnconsideredCandidates, lowestConsideredCandidatesScore);
        for (FTree tree : fr.getResults()) {
            tree.addAnnotation(UnconsideredCandidatesUpperBound.class, unconsideredCandidatesUpperBound);
        }
    }

    public List<IdentificationResult> identify(Ms2Experiment uexperiment, int numberOfCandidates, boolean recalibrating, IsotopePatternHandling deisotope) {
        return identify(uexperiment, numberOfCandidates, recalibrating, deisotope, (FormulaConstraints) null);
    }


    /**
     * Identify the molecular formula of the measured compound by combining an isotope pattern analysis on MS data with a fragmentation pattern analysis on MS/MS data
     *
     * @param uexperiment        input data
     * @param numberOfCandidates number of candidates to output
     * @param recalibrating      true if spectra should be recalibrated during tree computation
     * @param deisotope          set this to 'omit' to ignore isotope pattern, 'filter' to use it for selecting molecular formula candidates or 'score' to rerank the candidates according to their isotope pattern
     * @param formulaConstraints use if specific constraints on the molecular formulas shall be imposed (may be null)
     * @return a list of identified molecular formulas together with their tree
     */
    public List<IdentificationResult> identify(Ms2Experiment uexperiment, int numberOfCandidates, boolean recalibrating, IsotopePatternHandling deisotope, FormulaConstraints formulaConstraints) {
        final AbstractTreeComputationInstance instance = getTreeComputationImplementation(getMs2Analyzer(), uexperiment, numberOfCandidates);
        final ProcessedInput pinput = instance.validateInput();
        pinput.setAnnotation(ForbidRecalibration.class, recalibrating ? ForbidRecalibration.ALLOWED : ForbidRecalibration.FORBIDDEN);
        if (formulaConstraints != null) pinput.getMeasurementProfile().setFormulaConstraints(formulaConstraints);
        performMs1Analysis(instance, deisotope);
        SiriusJobs.getGlobalJobManager().submitJob(instance);
        AbstractTreeComputationInstance.FinalResult fr = instance.takeResult();
        final List<IdentificationResult> irs = createIdentificationResults(fr, instance);//postprocess results
        return irs;
    }

    public FormulaConstraints predictElementsFromMs1(Ms2Experiment experiment) {
        final SimpleSpectrum pattern = getMs1Analyzer().extractPattern(experiment, experiment.getIonMass());
        if (pattern == null) return null;
        return getElementPrediction().predictConstraints(pattern);
    }

    public IdentificationResult compute(Ms2Experiment experiment, MolecularFormula formula) {
        return compute(experiment, formula, true);
    }

    public BasicJJob<IdentificationResult> makeComputeJob(Ms2Experiment experiment, MolecularFormula formula) {
        final AbstractTreeComputationInstance instance = getTreeComputationImplementation(getMs2Analyzer(), experiment, 1);
        final ProcessedInput pinput = instance.validateInput();
        pinput.setAnnotation(Whiteset.class, Whiteset.of(formula));
        pinput.setAnnotation(ForbidRecalibration.class, ForbidRecalibration.ALLOWED);
        return instance.wrap((f) -> new IdentificationResult(f.getResults().get(0), 1));
    }

    /**
     * Compute a fragmentation tree for the given MS/MS data using the given neutral molecular formula as explanation for the measured compound
     *
     * @param experiment    input data
     * @param formula       neutral molecular formula of the measured compound
     * @param recalibrating true if spectra should be recalibrated during tree computation
     * @return A single instance of IdentificationResult containing the computed fragmentation tree
     */
    public IdentificationResult compute(Ms2Experiment experiment, MolecularFormula formula, boolean recalibrating) {
        final AbstractTreeComputationInstance instance = getTreeComputationImplementation(getMs2Analyzer(), experiment, 1);
        final ProcessedInput pinput = instance.validateInput();
        pinput.setAnnotation(Whiteset.class, Whiteset.of(formula));
        pinput.setAnnotation(ForbidRecalibration.class, recalibrating ? ForbidRecalibration.ALLOWED : ForbidRecalibration.FORBIDDEN);
        SiriusJobs.getGlobalJobManager().submitJob(instance);
        final IdentificationResult ir = new IdentificationResult(instance.takeResult().getResults().get(0), 1);
        // tree is always beautyfied
        if (recalibrating) ir.setBeautifulTree(ir.getRawTree());
        return ir;

    }


    public boolean beautifyTree(IdentificationResult result, Ms2Experiment experiment) {
        return beautifyTree(null, result, experiment, true);
    }

    /**
     * compute and set the beautiful version of the {@link IdentificationResult}s {@link FTree}.
     * Aka: try to find a {@link FTree} with the same root molecular formula which explains the desired amount of the spectrum - if necessary by increasing the tree size scorer.
     *
     * @param result
     * @param experiment
     * @return true if a beautiful tree was found
     */
    public boolean beautifyTree(IdentificationResult result, Ms2Experiment experiment, boolean recalibrating) {
        return beautifyTree(null, result, experiment, recalibrating);
    }

    public boolean beautifyTree(MasterJJob<?> master, IdentificationResult result, Ms2Experiment experiment, boolean recalibrating) {
        if (result.getBeautifulTree() != null) return true;
        FTree beautifulTree = beautifyTree(master, result.getStandardTree(), experiment, recalibrating);
        if (beautifulTree != null) {
            result.setBeautifulTree(beautifulTree);
            return true;
        }
        return false;
    }

    public FTree beautifyTree(FTree tree, Ms2Experiment experiment, boolean recalibrating) {
        return beautifyTree(null, tree, experiment, recalibrating);
    }

    public FTree beautifyTree(MasterJJob<?> master, FTree tree, Ms2Experiment experiment, boolean recalibrating) {
        if (tree.getAnnotation(Beautified.class, Beautified.IS_UGGLY).isBeautiful()) return tree;
        final PrecursorIonType ionType = tree.getAnnotationOrThrow(PrecursorIonType.class);
        final MutableMs2Experiment mexp = new MutableMs2Experiment(experiment);
        mexp.setPrecursorIonType(ionType);
        final MolecularFormula formula;
        switch (tree.getAnnotation(IonTreeUtils.Type.class, IonTreeUtils.Type.RAW)) {
            case RESOLVED:
                if (ionType.isIntrinsicalCharged())
                    formula = ionType.measuredNeutralMoleculeToNeutralMolecule(tree.getRoot().getFormula());
                else
                    formula = tree.getRoot().getFormula();
                break;
            case IONIZED:
                formula = ionType.precursorIonToNeutralMolecule(tree.getRoot().getFormula());
                break;
            case RAW:
            default:
                formula = ionType.measuredNeutralMoleculeToNeutralMolecule(tree.getRoot().getFormula());
                ;
                break;
        }
        //todo remove when cleaning up the api
        final FTree btree;
        if (master != null) {
            btree = master.submitSubJob(FasterTreeComputationInstance.beautify(getMs2Analyzer(), tree)).takeResult().getResults().get(0);
        } else {
            btree = SiriusJobs.getGlobalJobManager().submitJob(FasterTreeComputationInstance.beautify(getMs2Analyzer(), tree)).takeResult().getResults().get(0);
        }


        if (!btree.getAnnotation(Beautified.class, Beautified.IS_UGGLY).isBeautiful()) {
            LoggerFactory.getLogger(Sirius.class).warn("Tree beautification annotation is not properly set.");
            btree.setAnnotation(Beautified.class, Beautified.IS_BEAUTIFUL);
        }
        return btree;
    }


    ////////////////////////////////////////////////////////////////////////////////

    public MutableMs2Experiment makeMutable(Ms2Experiment experiment) {
        if (experiment instanceof MutableMs2Experiment) return (MutableMs2Experiment) experiment;
        else return new MutableMs2Experiment(experiment);
    }


    public void setAllowedIonModes(Ms2Experiment experiment, Ionization... ionModes) {
        final PossibleIonModes pa = new PossibleIonModes();
        for (Ionization ion : ionModes) {
            pa.add(ion, 1d);
        }
        experiment.setAnnotation(PossibleIonModes.class, pa);
    }
    public void setAllowedIonModes(ProcessedInput experiment, Ionization... ionModes) {
        final PossibleIonModes pa = new PossibleIonModes();
        for (Ionization ion : ionModes) {
            pa.add(ion, 1d);
        }
        experiment.setAnnotation(PossibleIonModes.class, pa);
    }

    public void setAllowedMassDeviation(MutableMs2Experiment experiment, Deviation fragmentMassDeviation) {
        MutableMeasurementProfile prof = makeProfile(experiment);
        prof.setAllowedMassDeviation(fragmentMassDeviation);
    }

    private MutableMeasurementProfile makeProfile(MutableMs2Experiment experiment) {
        MeasurementProfile prof = experiment.getAnnotation(MeasurementProfile.class, null);
        if (prof == null) {
            MutableMeasurementProfile prof2 = new MutableMeasurementProfile();
            experiment.setAnnotation(MeasurementProfile.class, prof2);
            return prof2;
        } else if (prof instanceof MutableMeasurementProfile) {
            return (MutableMeasurementProfile) prof;
        } else {
            MutableMeasurementProfile prof2 = new MutableMeasurementProfile(prof);
            experiment.setAnnotation(MeasurementProfile.class, prof2);
            return prof2;
        }
    }

    public void setIonModeWithProbability(Ms2Experiment experiment, Ionization ion, double probability) {
        final PossibleIonModes pa = experiment.getAnnotation(PossibleIonModes.class, new PossibleIonModes());
        pa.add(ion, probability);
        experiment.setAnnotation(PossibleIonModes.class, pa);
    }

    public void setAllowedAdducts(Ms2Experiment experiment, PrecursorIonType... adducts) {
        final PossibleAdducts ad = new PossibleAdducts(adducts);
        experiment.setAnnotation(PossibleAdducts.class, ad);
    }
    public void setAllowedAdducts(ProcessedInput processedInput, PrecursorIonType... adducts) {
        final PossibleAdducts ad = new PossibleAdducts(adducts);
        processedInput.setAnnotation(PossibleAdducts.class, ad);
    }

    public void setFormulaSearchList(Ms2Experiment experiment, MolecularFormula... formulas) {
        setFormulaSearchList(experiment, Arrays.asList(formulas));
    }

    public void setFormulaSearchList(Ms2Experiment experiment, Iterable<MolecularFormula> formulas) {
        final HashSet<MolecularFormula> fs = new HashSet<MolecularFormula>();
        for (MolecularFormula f : formulas) fs.add(f);
        final Whiteset whiteset = new Whiteset(fs);
        experiment.setAnnotation(Whiteset.class, whiteset);
    }

    public void enableRecalibration(MutableMs2Experiment experiment, boolean enabled) {
        experiment.setAnnotation(ForbidRecalibration.class, enabled ? ForbidRecalibration.ALLOWED : ForbidRecalibration.FORBIDDEN);
    }

    public void setIsotopeMode(MutableMs2Experiment experiment, IsotopePatternHandling handling) {
        FormulaSettings current = experiment.getAnnotation(FormulaSettings.class, FormulaSettings.defaultWithMs2Only());
        if (handling.isFiltering()) current = current.withIsotopeFormulaFiltering();
        else current = current.withoutIsotopeFormulaFiltering();
        experiment.setAnnotation(FormulaSettings.class, current);
        if (handling.isScoring()) {
            experiment.setAnnotation(IsotopeScoring.class, IsotopeScoring.DEFAULT);
        } else {
            experiment.setAnnotation(IsotopeScoring.class, IsotopeScoring.DISABLED);
        }
    }

    public void setAutomaticElementDetectionFor(Ms2Experiment experiment, Element elements) {
        FormulaSettings current = experiment.getAnnotation(FormulaSettings.class, FormulaSettings.defaultWithMs2Only());
        experiment.setAnnotation(FormulaSettings.class, current.withoutAutoDetect().autoDetect(elements));
    }

    public void setFormulaConstraints(Ms2Experiment experiment, FormulaConstraints constraints) {
        FormulaSettings current = experiment.getAnnotation(FormulaSettings.class, FormulaSettings.defaultWithMs2Only());
        experiment.setAnnotation(FormulaSettings.class, current.withConstraints(constraints));
    }

    public void enableAutomaticElementDetection(Ms2Experiment experiment, boolean enabled) {
        FormulaSettings current = experiment.getAnnotation(FormulaSettings.class, FormulaSettings.defaultWithMs2Only());
        if (enabled) {
            experiment.setAnnotation(FormulaSettings.class, current.autoDetect(getElementPrediction().getChemicalAlphabet().getElements().toArray(new Element[0])));
        } else {
            experiment.setAnnotation(FormulaSettings.class, current.withoutAutoDetect());
        }
    }

    public void setTimeout(MutableMs2Experiment experiment, int timeoutPerInstanceInSeconds, int timeoutPerDecompositionInSeconds) {
        experiment.setAnnotation(Timeout.class, Timeout.newTimeout(timeoutPerInstanceInSeconds, timeoutPerDecompositionInSeconds));
    }

    public void disableTimeout(MutableMs2Experiment experiment) {
        experiment.setAnnotation(Timeout.class, Timeout.NO_TIMEOUT);
    }


    ////////////////////////////////////////////////////////////////////////////////



    /*
        DATA STRUCTURES API CALLS
     */

    /**
     * Wraps an array of m/z values and and array of intensity values into a spectrum object that can be used by the SIRIUS library. The resulting spectrum is a lightweight view on the array, so changes in the array are reflected in the spectrum. The spectrum object itself is immutable.
     *
     * @param mz          mass to charge ratios
     * @param intensities intensity values. Can be normalized or absolute - SIRIUS will performNormalization them itself at later point
     * @return view on the arrays implementing the Spectrum interface
     */
    public Spectrum<Peak> wrapSpectrum(double[] mz, double[] intensities) {
        return Spectrums.wrap(mz, intensities);
    }

    /**
     * Lookup the symbol in the periodic table and returns the corresponding Element object or null if no element with this symbol exists.
     *
     * @param symbol symbol of the element, e.g. H for hydrogen or Cl for chlorine
     * @return instance of Element class
     */
    public Element getElement(String symbol) {
        return table.getByName(symbol);
    }

    /**
     * Lookup the ionization name and returns the corresponding ionization object or null if no ionization with this name is registered. The name of an ionization has the syntax [M+ADDUCT]CHARGE, for example [M+H]+ or [M-H]-.
     * <p>
     * Deprecated: Ionization is now for the ion-mode (protonation or deprotonation, number of charges, ...). Use
     * getPrecursorIonType to get a PrecursorIonType object that contains adducts and in-source fragmentation as well as
     * the ion mode of the precursor ion
     *
     * @param name name of the ionization
     * @return adduct object
     */
    @Deprecated
    public Ionization getIonization(String name) {
        return getPrecursorIonType(name).getIonization();
    }

    /**
     * Lookup the ionization name and returns the corresponding ionization object or null if no ionization with this name is registered. The name of an ionization has the syntax [M+ADDUCT]CHARGE, for example [M+H]+ or [M-H]-.
     *
     * @param name name of the ionization
     * @return adduct object
     */
    public PrecursorIonType getPrecursorIonType(String name) {
        return table.ionByName(name);
    }


    /**
     * Charges are subclasses of Ionization. So they can be used everywhere as replacement for ionizations. A charge is very similar to the [M]+ and [M]- ionizations. However, the difference is that [M]+ describes an intrinsically charged compound where the Charge +1 describes an compound with unknown adduct.
     *
     * @param charge either 1 for positive or -1 for negative charges.
     * @return
     */
    public Charge getCharge(int charge) {
        if (charge != -1 && charge != 1)
            throw new IllegalArgumentException("SIRIUS does not support multiple charged compounds");
        return new Charge(charge);
    }

    /**
     * Creates a Deviation object that describes a mass deviation as maximum of a relative term (in ppm) and an absolute term. Usually, mass accuracy is given as relative term in ppm, as measurement errors increase with higher masses. However, for very small compounds (and fragments!) these relative values might overestimate the mass accurary. Therefore, an absolute value have to be given.
     *
     * @param ppm mass deviation as relative value (in ppm)
     * @param abs mass deviation as absolute value (m/z)
     * @return Deviation object
     */
    public Deviation getMassDeviation(int ppm, double abs) {
        return new Deviation(ppm, abs);
    }

    /**
     * Creates a Deviation object with the given relative term. The absolute term is implicitly given by applying the relative term on m/z 100.
     *
     * @param ppm
     * @return
     */
    public Deviation getMassDeviation(int ppm) {
        return new Deviation(ppm);
    }

    /**
     * Parses a molecular formula from the given string
     *
     * @param f molecular formula (e.g. in Hill notation)
     * @return immutable molecular formula object
     */
    public MolecularFormula parseFormula(String f) {
        return MolecularFormula.parse(f);
    }

    /**
     * Creates a Ms2Experiment object from the given MS and MS/MS spectra. A Ms2Experiment is NOT a single run or measurement, but a measurement of a concrete compound. So a MS spectrum might contain several Ms2Experiments. However, each MS/MS spectrum should have on precursor or parent mass. All MS/MS spectra with the same precursor together with the MS spectrum containing this precursor peak can be seen as one Ms2Experiment.
     *
     * @param formula neutral molecular formula of the compound
     * @param ion     ionization mode (can be an instance of Charge if the exact adduct is unknown)
     * @param ms1     the MS spectrum containing the isotope pattern of the measured compound. Might be null
     * @param ms2     a list of MS/MS spectra containing the fragmentation pattern of the measured compound
     * @return a MS2Experiment instance, ready to be analyzed by SIRIUS
     */
    public Ms2Experiment getMs2Experiment(MolecularFormula formula, Ionization ion, Spectrum<Peak> ms1, Spectrum... ms2) {
        return getMs2Experiment(formula, PrecursorIonType.getPrecursorIonType(ion), ms1, ms2);
    }

    /**
     * Creates a Ms2Experiment object from the given MS and MS/MS spectra. A Ms2Experiment is NOT a single run or measurement, but a measurement of a concrete compound. So a MS spectrum might contain several Ms2Experiments. However, each MS/MS spectrum should have on precursor or parent mass. All MS/MS spectra with the same precursor together with the MS spectrum containing this precursor peak can be seen as one Ms2Experiment.
     *
     * @param formula neutral molecular formula of the compound
     * @param ion     PrecursorIonType (contains ion mode as well as adducts and in-source fragmentations of the precursor ion)
     * @param ms1     the MS spectrum containing the isotope pattern of the measured compound. Might be null
     * @param ms2     a list of MS/MS spectra containing the fragmentation pattern of the measured compound
     * @return a MS2Experiment instance, ready to be analyzed by SIRIUS
     */
    public Ms2Experiment getMs2Experiment(MolecularFormula formula, PrecursorIonType ion, Spectrum<Peak> ms1, Spectrum... ms2) {
        final MutableMs2Experiment exp = (MutableMs2Experiment) getMs2Experiment(ion.neutralMassToPrecursorMass(formula.getMass()), ion, ms1, ms2);
        exp.setMolecularFormula(formula);
        return exp;
    }

    /**
     * Creates a Ms2Experiment object from the given MS and MS/MS spectra. A Ms2Experiment is NOT a single run or measurement, but a measurement of a concrete compound. So a MS spectrum might contain several Ms2Experiments. However, each MS/MS spectrum should have on precursor or parent mass. All MS/MS spectra with the same precursor together with the MS spectrum containing this precursor peak can be seen as one Ms2Experiment.
     *
     * @param parentMass the measured mass of the precursor ion. Can be either the MS peak or (if present) a MS/MS peak
     * @param ion        PrecursorIonType (contains ion mode as well as adducts and in-source fragmentations of the precursor ion)
     * @param ms1        the MS spectrum containing the isotope pattern of the measured compound. Might be null
     * @param ms2        a list of MS/MS spectra containing the fragmentation pattern of the measured compound
     * @return a MS2Experiment instance, ready to be analyzed by SIRIUS
     */
    public Ms2Experiment getMs2Experiment(double parentMass, PrecursorIonType ion, Spectrum<Peak> ms1, Spectrum... ms2) {
        final MutableMs2Experiment mexp = new MutableMs2Experiment();
        mexp.setPrecursorIonType(ion);
        mexp.setIonMass(parentMass);
        for (Spectrum<Peak> spec : ms2) {
            mexp.getMs2Spectra().add(new MutableMs2Spectrum(spec, mexp.getIonMass(), CollisionEnergy.none(), 2));
        }
        return mexp;
    }

    /**
     * Creates a Ms2Experiment object from the given MS and MS/MS spectra. A Ms2Experiment is NOT a single run or measurement, but a measurement of a concrete compound. So a MS spectrum might contain several Ms2Experiments. However, each MS/MS spectrum should have on precursor or parent mass. All MS/MS spectra with the same precursor together with the MS spectrum containing this precursor peak can be seen as one Ms2Experiment.
     *
     * @param parentMass the measured mass of the precursor ion. Can be either the MS peak or (if present) a MS/MS peak
     * @param ion        ionization mode (can be an instance of Charge if the exact adduct is unknown)
     * @param ms1        the MS spectrum containing the isotope pattern of the measured compound. Might be null
     * @param ms2        a list of MS/MS spectra containing the fragmentation pattern of the measured compound
     * @return a MS2Experiment instance, ready to be analyzed by SIRIUS
     */
    public Ms2Experiment getMs2Experiment(double parentMass, Ionization ion, Spectrum<Peak> ms1, Spectrum... ms2) {
        return getMs2Experiment(parentMass, PrecursorIonType.getPrecursorIonType(ion), ms1, ms2);
    }

    /**
     * Formula Constraints consist of a chemical alphabet (a subset of the periodic table, determining which elements might occur in the measured compounds) and upperbounds for each of this elements. A formula constraint can be given like a molecular formula. Upperbounds are written in square brackets or omitted, if any number of this element should be allowed.
     *
     * @param constraints string representation of the constraint, e.g. "CHNOP[5]S[20]"
     * @return formula constraint object
     */
    public FormulaConstraints getFormulaConstraints(String constraints) {
        return new FormulaConstraints(constraints);
    }

    /**
     * Decomposes a mass and return a list of all molecular formulas which ionized mass is near the measured mass.
     * The maximal distance between the neutral mass of the measured ion and the theoretical mass of the decomposed formula depends on the chosen profile. For qtof it is 10 ppm, for Orbitrap and FTICR it is 5 ppm.
     *
     * @param mass   mass of the measured ion
     * @param ion    ionization mode (might be a Charge, in which case the decomposer will enumerate the ion formulas instead of the neutral formulas)
     * @param constr the formula constraints, defining the allowed elements and their upperbounds
     * @return list of molecular formulas which theoretical ion mass is near the given mass
     */
    public List<MolecularFormula> decompose(double mass, Ionization ion, FormulaConstraints constr) {
        return decompose(mass, ion, constr, getMs2Analyzer().getDefaultProfile().getAllowedMassDeviation());
    }

    /**
     * Decomposes a mass and return a list of all molecular formulas which ionized mass is near the measured mass
     *
     * @param mass   mass of the measured ion
     * @param ion    ionization mode (might be a Charge, in which case the decomposer will enumerate the ion formulas instead of the neutral formulas)
     * @param constr the formula constraints, defining the allowed elements and their upperbounds
     * @param dev    the allowed mass deviation of the measured ion from the theoretical ion masses
     * @return
     */
    public List<MolecularFormula> decompose(double mass, Ionization ion, FormulaConstraints constr, Deviation dev) {
        return getMs2Analyzer().getDecomposerFor(constr.getChemicalAlphabet()).decomposeToFormulas(ion.subtractFromMass(mass), dev, constr);
    }

    /**
     * Applies a given biotransformation on a given Molecular formular and return the transformed formula(s)
     *
     * @param source         source formula for transformation
     * @param transformation to that will be applied to given Formula    ionization mode (might be a Charge, in which case the decomposer will enumerate the ion formulas instead of the neutral formulas)
     * @return transformed MolecularFormulas
     */
    public List<MolecularFormula> bioTransform(MolecularFormula source, BioTransformation transformation) {
        return BioTransformer.transform(source, transformation);
    }


    /**
     * Applies all known biotransformation on a given Molecular formular and returns the transformed formula(s)
     *
     * @param source source formula for transformation
     * @return transformed MolecularFormulas
     */
    public List<MolecularFormula> bioTransform(MolecularFormula source) {
        return BioTransformer.getAllTransformations(source);
    }

    /**
     * Simulates an isotope pattern for the given molecular formula and the chosen ionization
     *
     * @param compound neutral molecular formula
     * @param ion      ionization mode (might be a Charge)
     * @return spectrum containing the theoretical isotope pattern of this compound
     */
    public Spectrum<Peak> simulateIsotopePattern(MolecularFormula compound, Ionization ion) {
        return getMs1Analyzer().getPatternGenerator().simulatePattern(compound, ion);
    }

    /**
     * Simulates an isotope pattern for the given molecular formula and the chosen ionization
     *
     * @param compound      neutral molecular formula
     * @param ion           ionization mode (might be a Charge)
     * @param numberOfPeaks number of peaks in simulated pattern
     * @return spectrum containing the theoretical isotope pattern of this compound
     */
    public Spectrum<Peak> simulateIsotopePattern(MolecularFormula compound, Ionization ion, int numberOfPeaks) {
        IsotopePatternGenerator gen = getMs1Analyzer().getPatternGenerator();
        gen.setMaximalNumberOfPeaks(numberOfPeaks);
        return gen.simulatePattern(compound, ion);
    }

    /**
     * depending on the isotope pattern policy this method is
     * - omit: doing nothing
     * - scoring: adds all isotope pattern candidates with their score into the hashmap
     * - filtering: adds only a subset of isotope pattern candidates with good scores into the hashmap
     *
     * @return score of the best isotope candidate
     */
    private double filterCandidateList(List<IsotopePattern> candidates, HashMap<MolecularFormula, IsotopePattern> formulas, IsotopePatternHandling handling) {
        if (handling == IsotopePatternHandling.omit) {
            return 0d;
        }
        if (candidates.size() == 0) return 0d;
        {
            double opt = Double.NEGATIVE_INFINITY;
            final SupportVectorMolecularFormulaScorer formulaScorer = new SupportVectorMolecularFormulaScorer();
            for (IsotopePattern p : candidates) {
                opt = Math.max(opt, p.getScore() + formulaScorer.score(p.getCandidate()));
            }
            if (opt < 0) {
                for (IsotopePattern p : candidates)
                    formulas.put(p.getCandidate(), new IsotopePattern(p.getCandidate(), 0d, p.getPattern()));
                return candidates.get(0).getScore();
            }
        }
        final double optscore = candidates.get(0).getScore();
        if (!handling.isFiltering()) {
            for (IsotopePattern p : candidates) formulas.put(p.getCandidate(), p);
            return candidates.get(0).getScore();
        }
        formulas.put(candidates.get(0).getCandidate(), candidates.get(0));
        int n = 1;
        for (; n < candidates.size(); ++n) {
            final double score = candidates.get(n).getScore();
            final double prev = candidates.get(n - 1).getScore();
            if (((optscore - score) > 5) && (score <= 0 || score / optscore < 0.5 || score / prev < 0.5)) break;
        }
        for (int i = 0; i < n; ++i) formulas.put(candidates.get(i).getCandidate(), candidates.get(i));
        return optscore;
    }

    private static Comparator<FTree> TREE_SCORE_COMPARATOR = new Comparator<FTree>() {
        @Override
        public int compare(FTree o1, FTree o2) {
            return Double.compare(o1.getAnnotationOrThrow(TreeScoring.class).getOverallScore(), o2.getAnnotationOrThrow(TreeScoring.class).getOverallScore());
        }
    };


    private ExtractedIsotopePattern extractedIsotopePattern(ProcessedInput pinput) {
        ExtractedIsotopePattern pat = pinput.getAnnotation(ExtractedIsotopePattern.class, null);
        if (pat == null) {
            final SimpleSpectrum spectrum = getMs1Analyzer().extractPattern(mergeMs1Spec(pinput), pinput.getMeasurementProfile(), pinput.getExperimentInformation().getIonMass());
            pat = new ExtractedIsotopePattern(spectrum);
            pinput.setAnnotation(ExtractedIsotopePattern.class, pat);
        }
        return pat;
    }

    private SimpleSpectrum mergeMs1Spec(ProcessedInput pinput) {
        final MutableMs2Experiment experiment = pinput.getExperimentInformation();
        if (experiment.getMergedMs1Spectrum() != null) return experiment.getMergedMs1Spectrum();
        else if (experiment.getMs1Spectra().size() > 0) {
            experiment.setMergedMs1Spectrum(Spectrums.mergeSpectra(experiment.<Spectrum<Peak>>getMs1Spectra()));
            return experiment.getMergedMs1Spectrum();
        } else return new SimpleSpectrum(new double[0], new double[0]);
    }

    protected boolean performMs1Analysis(AbstractTreeComputationInstance instance) {
        FormulaSettings fs = instance.validateInput().getAnnotation(FormulaSettings.class,null);
        IsotopeScoring iso = instance.validateInput().getAnnotation(IsotopeScoring.class,null);
        if (fs==null || fs.isAllowIsotopeElementFiltering()) {
            if (iso==null || iso.getIsotopeScoreWeighting()>0) {
                return performMs1Analysis(instance,IsotopePatternHandling.both);
            } else return performMs1Analysis(instance,IsotopePatternHandling.filter);
        } else if (iso==null || iso.getIsotopeScoreWeighting()>0)
            return performMs1Analysis(instance,IsotopePatternHandling.score);
        else return performMs1Analysis(instance,IsotopePatternHandling.omit);
    }

    /*
    TODO: We have to move this at some point back into the FragmentationPatternAnalysis pipeline -_-
     */
    protected boolean performMs1Analysis(AbstractTreeComputationInstance instance, IsotopePatternHandling handling) {
        if (handling == IsotopePatternHandling.omit) return false;
        final ProcessedInput input = instance.validateInput();
        final ExtractedIsotopePattern pattern = extractedIsotopePattern(input);
        if (!pattern.hasPatternWithAtLeastTwoPeaks())
            return false; // we cannot do any analysis without isotope information
        // step 1: automatic element detection
        performAutomaticElementDetection(input, pattern.getPattern());

        // step 2: adduct type search
        PossibleIonModes pim = input.getAnnotation(PossibleIonModes.class, null);
        if (pim == null)
            detectPossibleIonModesFromMs1(input);
        else if (pim.isGuessFromMs1Enabled()) {
            detectPossibleIonModesFromMs1(input, pim.getIonModesAsPrecursorIonType().toArray(new PrecursorIonType[0]));
        }
        // step 3: Isotope pattern analysis
        if (input.getAnnotation(IsotopeScoring.class, IsotopeScoring.DEFAULT).getIsotopeScoreWeighting() <= 0)
            return false;
        final DecompositionList decompositions = instance.precompute().getAnnotationOrThrow(DecompositionList.class);
        final IsotopePatternAnalysis an = getMs1Analyzer();
        for (Map.Entry<Ionization, List<MolecularFormula>> entry : decompositions.getFormulasPerIonMode().entrySet()) {
            for (IsotopePattern pat : an.scoreFormulas(pattern.getPattern(), entry.getValue(), input.getExperimentInformation(), input.getMeasurementProfile(), PrecursorIonType.getPrecursorIonType(entry.getKey()))) {
                pattern.getExplanations().put(pat.getCandidate(), pat);
            }
        }
        int isoPeaks = 0;
        double maxScore = Double.NEGATIVE_INFINITY;
        for (IsotopePattern pat : pattern.getExplanations().values()) {
            maxScore = Math.max(pat.getScore(), maxScore);
            isoPeaks = Math.max(pat.getPattern().size(), isoPeaks);
        }
        // step 3: apply filtering and/or scoring
        if (maxScore >= MINIMAL_SCORE_FOR_APPLY_FILTER) {
            if (handling.isFiltering()) {
                //final Iterator<Map.Entry<MolecularFormula, IsotopePattern>> iter = pattern.getExplanations().entrySet().iterator();
                final Iterator<Decomposition> iter = decompositions.getDecompositions().iterator();
                while (iter.hasNext()) {
                    final Decomposition d = iter.next();
                    final IsotopePattern p = pattern.getExplanations().get(d.getCandidate());
                    if (p==null || p.getScore() < ((isoPeaks * ISOTOPE_SCORE_FILTER_THRESHOLD))) {
                        iter.remove();
                    }
                }
            }
        }
        final Iterator<Map.Entry<MolecularFormula, IsotopePattern>> iter = pattern.getExplanations().entrySet().iterator();
        while (iter.hasNext()) {
            final Map.Entry<MolecularFormula, IsotopePattern> val = iter.next();
            val.setValue(val.getValue().withScore(handling.isScoring() ? Math.max(val.getValue().getScore(), 0d) : 0d));
        }

        return true;
    }

    private void performAutomaticElementDetection(ProcessedInput input, SimpleSpectrum extractedPattern) {
        final FormulaSettings settings = input.getAnnotation(FormulaSettings.class, FormulaSettings.defaultWithMs1());
        if (settings.isElementDetectionEnabled()) {
            final ElementPredictor predictor = getElementPrediction();
            final HashSet<Element> allowedElements = new HashSet<>(input.getMeasurementProfile().getFormulaConstraints().getChemicalAlphabet().getElements());
            final HashSet<Element> auto = settings.getAutomaticDetectionEnabled();
            allowedElements.addAll(auto);
            Iterator<Element> e = allowedElements.iterator();
            final FormulaConstraints constraints = predictor.predictConstraints(extractedPattern);
            while (e.hasNext()) {
                final Element detectable = e.next();
                if (auto.contains(detectable) && getElementPrediction().isPredictable(detectable) && constraints.getUpperbound(detectable) <= 0)
                    e.remove();
            }
            final FormulaConstraints revised = settings.getConstraints().getExtendedConstraints(allowedElements.toArray(new Element[allowedElements.size()]));
            for (Element det : auto) {
                revised.setUpperbound(det, constraints.getUpperbound(det));
            }
            input.getMeasurementProfile().setFormulaConstraints(revised);
        }
    }
}
