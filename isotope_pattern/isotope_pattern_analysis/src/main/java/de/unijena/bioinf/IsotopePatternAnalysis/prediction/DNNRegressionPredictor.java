package de.unijena.bioinf.IsotopePatternAnalysis.prediction;

import de.unijena.bioinf.ChemistryBase.chem.ChemicalAlphabet;
import de.unijena.bioinf.ChemistryBase.chem.Element;
import de.unijena.bioinf.ChemistryBase.chem.FormulaConstraints;
import de.unijena.bioinf.ChemistryBase.chem.PeriodicTable;
import de.unijena.bioinf.ChemistryBase.ms.utils.SimpleSpectrum;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;

public class DNNRegressionPredictor implements ElementPredictor {

    protected TrainedElementDetectionNetwork[] networks;
    protected ChemicalAlphabet alphabet;
    protected double[] modifiers;

    public DNNRegressionPredictor() {
        this.networks = readNetworks();
        this.modifiers = new double[DETECTABLE_ELEMENTS.length];
        Arrays.fill(modifiers, 0.33);
        setModifiers("S", 1d);
        setModifiers("Si", 1d);
        final Element[] elems = new Element[FREE_ELEMENTS.length+DETECTABLE_ELEMENTS.length];
        System.arraycopy(FREE_ELEMENTS, 0, elems, 0, FREE_ELEMENTS.length);
        System.arraycopy(DETECTABLE_ELEMENTS, 0, elems, FREE_ELEMENTS.length, DETECTABLE_ELEMENTS.length);
        this.alphabet = new ChemicalAlphabet(elems);
    }

    public void setModifiers(double modifier) {
        Arrays.fill(modifiers, modifier);
    }

    public void setModifiers(String symbol, double modifier) {
        setModifier(PeriodicTable.getInstance().getByName(symbol), modifier);
    }

    public void setModifier(Element element, double threshold) {
        for (int i=0; i < DETECTABLE_ELEMENTS.length; ++i) {
            if (DETECTABLE_ELEMENTS[i].equals(element)) {
                modifiers[i] = threshold;
                return;
            }
        }
        throw new IllegalArgumentException(element.getSymbol() + " is not predictable");
    }

    private static TrainedElementDetectionNetwork[] readNetworks() {
        try {
            final TrainedElementDetectionNetwork fivePeaks = TrainedElementDetectionNetwork.readRegressionNetwork(DNNElementPredictor.class.getResourceAsStream("/regression5.param"));
            final TrainedElementDetectionNetwork fourPeaks = TrainedElementDetectionNetwork.readRegressionNetwork(DNNElementPredictor.class.getResourceAsStream("/regression4.param"));
            final TrainedElementDetectionNetwork threePeaks = TrainedElementDetectionNetwork.readRegressionNetwork(DNNElementPredictor.class.getResourceAsStream("/regression3.param"));
            return new TrainedElementDetectionNetwork[]{fivePeaks, fourPeaks, threePeaks};
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private final static Element[] DETECTABLE_ELEMENTS;
    private final static int[] UPPERBOUNDS, FREE_UPPERBOUNDS;
    private final static Element[] FREE_ELEMENTS;
    private final static Element SELENE;

    static {
        PeriodicTable T = PeriodicTable.getInstance();
        DETECTABLE_ELEMENTS = new Element[]{
                T.getByName("B"),
                T.getByName("Br"),
                T.getByName("Cl"),
                T.getByName("S"),
                T.getByName("Si"),
                T.getByName("Se"),
        };
        FREE_ELEMENTS = new Element[]{
                T.getByName("C"),
                T.getByName("H"),
                T.getByName("N"),
                T.getByName("O"),
                T.getByName("P"),
                T.getByName("F"),
                T.getByName("I")
        };
        UPPERBOUNDS = new int[]{2,5,5,10,2,2};
        FREE_UPPERBOUNDS = new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 10, 20, 6};
        SELENE = T.getByName("Se");
    }

    @Override
    public FormulaConstraints predictConstraints(SimpleSpectrum pickedPattern) {
        final HashMap<Element, Integer> elements = new HashMap<>(10);
        for (Element e : FREE_ELEMENTS)
            elements.put(e, Integer.MAX_VALUE);
        // special case for selene
        if (pickedPattern.size() > 5) {
            double intensityAfterFifth = 0d;
            for (int i=pickedPattern.size()-1; i >= 5; --i) {
                intensityAfterFifth += pickedPattern.getIntensityAt(i);
            }
            double intensityBeforeFifth = 0d;
            for (int i=0; i < 5; ++i) {
                intensityBeforeFifth += pickedPattern.getIntensityAt(i);
            }
            intensityAfterFifth /= intensityBeforeFifth;
            if (intensityAfterFifth > 0.25) elements.put(SELENE, 1);
        }
        for (TrainedElementDetectionNetwork network : networks) {
            if (network.numberOfPeaks() <= pickedPattern.size() ) {
                final double[] prediction = network.predict(pickedPattern);
                for (int i=0; i < prediction.length; ++i) {
                    final Element e = DETECTABLE_ELEMENTS[i];
                    int number = (int)Math.ceil(prediction[i]-0.22);
                    if (number > 0) number = (int)Math.ceil(prediction[i]+modifiers[i]);
                    if (elements.containsKey(e)) elements.put(e, Math.max(elements.get(e), number));
                    else elements.put(e, number);
                }
                break;
            }
        }
        {
            final Iterator<Element> iter = elements.keySet().iterator();
            while (iter.hasNext()) {
                if (elements.get(iter.next())<=0) iter.remove();
            }
        }
        final ChemicalAlphabet alphabet = new ChemicalAlphabet(elements.keySet().toArray(new Element[elements.size()]));
        final FormulaConstraints constraints = new FormulaConstraints(alphabet);
        for (int i=0; i < FREE_UPPERBOUNDS.length; ++i) {
            constraints.setUpperbound(FREE_ELEMENTS[i], FREE_UPPERBOUNDS[i]);
        }
        for (int i=0; i < UPPERBOUNDS.length; ++i) {
            if (elements.containsKey(DETECTABLE_ELEMENTS[i]))
                constraints.setUpperbound(DETECTABLE_ELEMENTS[i], elements.get(DETECTABLE_ELEMENTS[i]));
        }
        return constraints;
    }

    @Override
    public ChemicalAlphabet getChemicalAlphabet() {
        return alphabet;
    }

    @Override
    public boolean isPredictable(Element element) {
        for (Element detectable : DETECTABLE_ELEMENTS) {
            if (detectable.equals(element)) return true;
        }
        return false;
    }
}
