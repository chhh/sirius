/*
 *  This file is part of the SIRIUS library for analyzing MS and MS/MS data
 *
 *  Copyright (C) 2013-2015 Kai Dührkop
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
package de.unijena.bioinf.FragmentationTreeConstruction.computation;

import de.unijena.bioinf.ChemistryBase.chem.Charge;
import de.unijena.bioinf.ChemistryBase.chem.MolecularFormula;
import de.unijena.bioinf.ChemistryBase.chem.PeriodicTable;
import de.unijena.bioinf.ChemistryBase.ms.*;
import de.unijena.bioinf.ChemistryBase.ms.utils.SimpleMutableSpectrum;
import de.unijena.bioinf.ChemistryBase.ms.utils.SimpleSpectrum;
import de.unijena.bioinf.babelms.GenericParser;
import de.unijena.bioinf.babelms.ms.JenaMsParser;
import org.junit.Test;

import java.io.IOException;

public class PreprocessingTest {

    public Ms2Experiment getExperimentData() {
        final JenaMsParser parser = new JenaMsParser();
        final GenericParser<Ms2Experiment> genericParser = new GenericParser<Ms2Experiment>(parser);
        try {
            return genericParser.parse(PreprocessingTest.class.getResourceAsStream("/testfile.ms"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public MutableMs2Experiment testData() {
        final MutableMs2Experiment experiment = new MutableMs2Experiment();
        experiment.setPrecursorIonType(PeriodicTable.getInstance().ionByName("[M]+"));
        experiment.setIonMass(180.0633881184 + new Charge(1).getMass());
        experiment.setMoleculeNeutralMass(180.0633881184);
        experiment.setMolecularFormula(MolecularFormula.parse("C6H12O6"));
        final SimpleMutableSpectrum sp = new SimpleMutableSpectrum();
        final double c = new Charge(1).getMass();
        final double parent = 180.0633881184 + c;
        sp.addPeak(new Peak(parent, 1.0));
        experiment.getMs1Spectra().add(new SimpleSpectrum(sp));
        experiment.setMergedMs1Spectrum(new SimpleSpectrum(sp));
        {
            final SimpleMutableSpectrum sp2 = new SimpleMutableSpectrum();
            sp2.addPeak(new Peak(parent, 320));
            sp2.addPeak(new Peak(MolecularFormula.parse("C6H10O5").getMass() + c, 100));
            sp2.addPeak(new Peak(MolecularFormula.parse("C5H12O4").getMass() + c, 75));
            final MutableMs2Spectrum ms2 = new MutableMs2Spectrum(sp2, parent, new CollisionEnergy(0, 10), 2);
            experiment.getMs2Spectra().add(ms2);
        }
        {
            final SimpleMutableSpectrum sp2 = new SimpleMutableSpectrum();
            sp2.addPeak(new Peak(parent, 21));
            sp2.addPeak(new Peak(MolecularFormula.parse("C5H6O5").getMass() + c, 22));
            sp2.addPeak(new Peak(MolecularFormula.parse("C4H6O3").getMass() + c, 11));
            final MutableMs2Spectrum ms2 = new MutableMs2Spectrum(sp2, parent, new CollisionEnergy(0, 10), 2);
            experiment.getMs2Spectra().add(ms2);
        }
        return experiment;
    }

    /**
     * As the preprocessing is a pipeline consisting of many (more or less) complex steps,
     * it is hard to "UNIT" test it. An quick'n dirty approach is to test the whole preprocessing
     * pipeline in one method.
     */
    @Test
    public void testPreprocessing() {
        /*
        Ms2ExperimentImpl experiment = new Ms2ExperimentImpl(getExperimentData());
        experiment.setMeasurementProfile(new ProfileImpl(new Deviation(10), new Deviation(5), new Deviation(20),
                FormulaConstraints.create(new ValenceFilter(), "C", "H", "N", "O", "P", "S")));
        // configure analysis
        final FragmentationPatternAnalysis analysis = new FragmentationPatternAnalysis();
        analysis.setInitial();
        analysis.setPostProcessors(Arrays.asList((PostProcessor)new NoiseThresholdFilter(0.015d)));
        analysis.setPeakMerger(new HighIntensityMerger());
        analysis.setInputValidators(Arrays.asList((Ms2ExperimentValidator)new MissingValueValidator()));
        analysis.setNormalizationType(NormalizationType.GLOBAL);
        // input validation
        experiment = new Ms2ExperimentImpl(analysis.validate(experiment));

        // input normalization
        List<ProcessedPeak> normalizedPeaks = analysis.performNormalization(experiment);

        // input merging
        List<ProcessedPeak> mergedPeaks = analysis.performPeakMerging(experiment, normalizedPeaks);

        // postprocessing
        mergedPeaks = new ArrayList<ProcessedPeak>(analysis.postProcess(PostProcessor.Stage.AFTER_MERGING, new ProcessedInput(experiment, mergedPeaks, null, null)).getMergedPeaks());

        Collections.sort(mergedPeaks, new ProcessedPeak.MassComparator());
        assertEquals(mergedPeaks.size(), 25);

        // first peak is parent peak. Take parent mass from ms1
        assertEquals(mergedPeaks.get(24).getMz(), 132.076815319352d, 1e-6);
        // second peak is at 114.066386424197 Da
        assertEquals(mergedPeaks.get(23).getMz(), 115.049976864731, 1e-6);
        assertEquals(mergedPeaks.get(23).getRelativeIntensity(), 0.0250236, 1e-3);
        /*
        // 90.0553049770099 // should be preferred, as it has the lowest collision energy
        assertEquals(mergedPeaks.get(11).getMz(), 90.0553049770099d, 1e-6);
        assertEquals(mergedPeaks.get(11).getRelativeIntensity(), 210.18379000000002, 1e-3);
        // 115.049976864731 (1.08147 + 1.42089 = 2.50236) // has highest intensity
        assertEquals(mergedPeaks.get(10).getMz(), 115.049976864731, 1e-6);
        assertEquals(mergedPeaks.get(10).getRelativeIntensity(), 2.50236, 1e-3);
        // 114.066386424197 (2.88662 + 3.2572 + 0.626885 = 6.7707049999999995)
        assertEquals(mergedPeaks.get(9).getMz(),114.066386424197, 1e-6);
        assertEquals(mergedPeaks.get(9).getRelativeIntensity(), 6.7707049999999995, 1e-3);


        // parent peak detection
        ProcessedPeak parentPeak = analysis.selectParentPeakAndCleanSpectrum(experiment, mergedPeaks);
        */

    }

}
