package de.unijena.bioinf.FragmentationTreeConstruction.computation;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import de.unijena.bioinf.ChemistryBase.algorithm.Scored;
import de.unijena.bioinf.ChemistryBase.chem.MolecularFormula;
import de.unijena.bioinf.ChemistryBase.ms.ft.FGraph;
import de.unijena.bioinf.ChemistryBase.ms.ft.FTree;
import de.unijena.bioinf.ChemistryBase.ms.ft.TreeScoring;
import de.unijena.bioinf.FragmentationTreeConstruction.ftheuristics.solver.CriticalPathSolver;
import de.unijena.bioinf.FragmentationTreeConstruction.model.DecompositionList;
import de.unijena.bioinf.FragmentationTreeConstruction.model.ProcessedInput;

import java.util.*;
import java.util.concurrent.*;

/**
 * - compute fragmentation graphs in parallel
 * - compute heuristics in parallel
 * - use heuristics to estimate subset of molecular formulas to compute exactly
 * - (optional) compute trees in parallel if Gurobi is used
 */


public class FasterMultithreadedTreeComputation {

    /*
    I recommend to use queues whenever possible to schedule the jobs.
    Blocking Queues allow to limit the number of instances that can be enqueued at the same
    time (and therefore, avoid memory overflows)
     */
    private final BlockingQueue<Future<FGraph>> graphsToCompute;
    private final BlockingQueue<Future<FTree>> treesToCompute;

    /*
    You can use TreeMaps to store your heuristical or exact computed trees
     */
    private final TreeMap<MolecularFormula, FTree> map;
    private final Multimap<Double, MolecularFormula> multimap = Multimaps.synchronizedListMultimap(
            ArrayListMultimap.<Double, MolecularFormula>create());


    /*
    The underlying framework
     */
    private final FragmentationPatternAnalysis analyzer;

    /*
    The actual input data
     */
    private ProcessedInput input;
    private HashMap<MolecularFormula, Scored<MolecularFormula>> formulasToConsider;
    private MolecularFormula poison_pill = MolecularFormula.emptyFormula();
    private FGraph poison_pill_FGraph = new FGraph();
    private FTree poison_pill_FTree = new FTree(poison_pill);
    private Queue<Future<Response>> futureRes = new ConcurrentLinkedQueue(); //TODO ueberlegen ob nicht blocking queue sinvoller

    public FasterMultithreadedTreeComputation(FragmentationPatternAnalysis analyzer) {
        this.graphsToCompute = new ArrayBlockingQueue<>(100);
        this.treesToCompute = new ArrayBlockingQueue<>(10);
        this.map = new TreeMap<>();
        this.analyzer = analyzer;

    }

    public void setInput(ProcessedInput input) {
        setInput(input, null);
    }

    public void setInput(ProcessedInput input, HashSet<MolecularFormula> formulas) {
        this.input = input;
        this.formulasToConsider = new HashMap<>();
        for (Scored<MolecularFormula> formula : input.getPeakAnnotationOrThrow(DecompositionList.class).get(input.getParentPeak()).getDecompositions()) {
            if (formulas == null || formulas.contains(formula.getCandidate()))
                formulasToConsider.put(formula.getCandidate(), formula);
        }
    }

    /**
     * start multi-threaded computation
     */
    private class CallToRemoteServiceFormula implements Callable<FGraph> {
        private final MolecularFormula formula;

        public CallToRemoteServiceFormula(MolecularFormula formula) {
            this.formula = formula;
        }

        @Override
        public FGraph call() throws Exception {
            if (formula == poison_pill) {
                return poison_pill_FGraph;
            }
            return computeGraph(formula);
        }

    }

    //Consumes Trees and builds multimaps.
    //in first test seemed single thread fully enough
    private class ConsumerTrees implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    final Future<FTree> heuTree = treesToCompute.take(); //waits until element available
                    FTree test = heuTree.get();
                    if (heuTree.get() == poison_pill_FTree) {
                        //damit scoreMap vollständig, vielleicht nicht mehr nötig
                        while (formulasToConsider.keySet().size() != multimap.size()) {
                            Thread.sleep(5);
                        }
                        break;
                    } else {
                        multimap.put(getScore(test), test.getRoot().getFormula());
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    System.out.println("Warning");
                } catch (ExecutionException e) {
                    e.printStackTrace();
                    System.out.println("Warning");
                }
            }
        }


    }

    private class Response {
        public int index;
        public double result;
        public double prediction;
    }

    public class Output extends Response {
        public double maxPrediction;
        public float cut;
        public int subsearch1;
        public int subsearch2;
        public int subsearch3;
        public int subsearch4;
        public int subsearch5;
        public int subsearch6;
        public int subsearch7;
        public int subsearch8;

        public int length;
        public long heuEndTime;

    }

    private class ConsumeGraphs implements Runnable {
        public void run() {
            int processors = Runtime.getRuntime().availableProcessors();
            ExecutorService service = Executors.newFixedThreadPool(processors);
            while (true) {
                try {

                    final Future<FGraph> graph = graphsToCompute.take();
                    // check if job is done (gets poison_pill)
                    if (graph.get() == poison_pill_FGraph) {
                        treesToCompute.put(service.submit(new Callable<FTree>() {
                            @Override
                            public FTree call() throws Exception {
//                            adds poison_pill tree to output
                                return poison_pill_FTree;
                            }
                        }));
                        service.shutdown();
                        break;
                    } else {
                        treesToCompute.put(service.submit(new Callable<FTree>() {
                            @Override
                            public FTree call() throws Exception {
                                return computeTreeHeuristically(graph.get());
                            }
                        }));
                    }


                } catch (Exception e) {
                    System.out.println("Warning");
                    e.printStackTrace();
                }
            }
        }

    }

    private class ConsumeGraphExact implements Runnable {
        private Double[] sortedHeuScores;
        private int thresh;

        ConsumeGraphExact(Double[] sortedHeuScores, int thresh) {
            this.sortedHeuScores = sortedHeuScores;
            this.thresh = thresh;
        }

        public void run() {
            int processors = Runtime.getRuntime().availableProcessors();
            ExecutorService service = Executors.newFixedThreadPool(processors);

            int j = multimap.size();        //irgendwas wie mul.size -50
            for (int i = sortedHeuScores.length - 1; i >= thresh; i--) {

                final int index = i;
                final Collection<MolecularFormula> formulas_for_exact_method = multimap.get(sortedHeuScores[i]);
                for (final MolecularFormula formula : formulas_for_exact_method) {
                    j--;                    //muss geändert werden
                    final int finalJ = j;  //
                    futureRes.add(service.submit(new Callable<Response>() {
                        @Override
                        public Response call() throws Exception {

                            final Response out = new Response();

                            out.index = finalJ;
                            out.result = getScore(computeTreeExactly(computeGraph(formula)));
                            out.prediction = sortedHeuScores[index];
                            return out;
                        }

                    }));
                }
            }

            service.shutdown();

        }
    }

    public Output startComputation() throws InterruptedException, ExecutionException {

        int processors = Runtime.getRuntime().availableProcessors();
        ExecutorService service = Executors.newFixedThreadPool(processors);

// oposite starting order to actual running order

//  Start consume trees and build multimap , single thread
        (new Thread(new ConsumerTrees())).start();

//  Start consume Graph and build heuristic Trees
        (new Thread(new ConsumeGraphs())).start();

//  Build graphs
        for (final MolecularFormula formula : formulasToConsider.keySet()) {
            //hier vieleicht anonyme Klasse anlegen siehe unten
            graphsToCompute.put(service.submit(new CallToRemoteServiceFormula(formula)));
        }
        graphsToCompute.put(service.submit(new CallToRemoteServiceFormula(poison_pill)));


//  wait until all trees consumed
        int x = formulasToConsider.keySet().size();
        while (multimap.size() != x) {
            Thread.sleep(5);
        }
        service.shutdownNow();

        Set<Double> heuScoreSet = multimap.keySet();
        Double[] sortedHeuScores = heuScoreSet.toArray(new Double[multimap.keySet().size()]);
        Arrays.sort(sortedHeuScores);
        long heuEndTime = System.currentTimeMillis();


//        new thresh method
        if (sortedHeuScores.length >50){
            int thresh = sortedHeuScores.length - 50;
            (new Thread(new ConsumeGraphExact(sortedHeuScores, thresh))).start();

        }
        else {
            int thresh = 0;
            (new Thread(new ConsumeGraphExact(sortedHeuScores, thresh))).start();
        }

//////        OLD //////////
/////        int thresh = (sortedHeuScores.length / 2); //if 0 than no speedup
//  calc best predited trees exactly

//////        (new Thread(new ConsumeGraphExact(sortedHeuScores, thresh))).start();

        int maxPosition = -1;
        double maxValue = -Double.MAX_VALUE;

//  initialize output
        Double[] presortedExScores = new Double[multimap.size()];
        Double[] predictedScores = new Double[multimap.size()];


        while (futureRes.size() < 1) {
            Thread.sleep(5);
        }

        List<Double> differenceList= new ArrayList<Double>();
        List<Double> portionList =  new ArrayList<Double>();
        List<Double> resultList= new ArrayList<Double>();


        while (futureRes.size() > 0) {
            Future<Response> future = futureRes.poll();
            Response response = future.get();
            presortedExScores[response.index] = response.result;
            predictedScores[response.index] = response.prediction;
            differenceList.add(response.result-response.prediction);
            portionList.add(response.result/response.prediction);
            resultList.add(response.result);
        }

        Output output = new Output();
        output.subsearch1=multimap.keySet().size();
        output.subsearch2=multimap.keySet().size();
        output.subsearch3=multimap.keySet().size();
        output.subsearch4=multimap.keySet().size();
        output.subsearch5=multimap.keySet().size();
        output.subsearch6=multimap.keySet().size();
        output.subsearch7=multimap.keySet().size();
        output.subsearch8=multimap.keySet().size();

        output.heuEndTime=heuEndTime;

//  bestimme den median
        if(resultList.size()>=50){
            Double[] sortedTop50Results = resultList.toArray(new Double[resultList.size()]);
            Double[] sortedDifferences = differenceList.toArray(new Double[differenceList.size()]);
            Double[] sortedPortions = portionList.toArray(new Double[portionList.size()]);

            Arrays.sort(sortedTop50Results);
            Arrays.sort(sortedDifferences);
            Arrays.sort(sortedPortions);

            double medianResult;
            double medianDifference;
            double medianPortion;
            int middle = sortedTop50Results.length/2;
            if (sortedTop50Results.length%2 == 1) {
                medianResult = sortedTop50Results[middle];
                medianDifference = sortedDifferences[middle];
                medianPortion = sortedPortions[middle];
            } else {
                medianResult =  (sortedTop50Results[middle-1] + sortedTop50Results[middle]) / 2.0;
                medianDifference =  (sortedDifferences[middle-1] + sortedDifferences[middle]) / 2.0;
                medianPortion =  (sortedPortions[middle-1] + sortedPortions[middle]) / 2.0;
            }
            int start1 =0;
            int start2 =0;
            int start3 =0;
            int start4 =0;
            int start5 =0;
            int start6 =0;
            int start7 =0;
            int start8 =0;

            // suche nach dem index des  ersten wert der zu klein ist.
            for(int i = sortedHeuScores.length-51 ; i >=0 ;i--){
//                median 50 exact scores <= heuscore(g) + median abweichung
                if(medianResult <= sortedHeuScores[i]+medianDifference){
                    start1++;
                }
//          seems to be too agressiv so now more conservativ
                if (medianResult<=sortedHeuScores[i]+sortedDifferences[sortedDifferences.length-1]){
                    start2++;
                }
                if(sortedTop50Results[0]<=sortedHeuScores[i]+sortedDifferences[sortedDifferences.length-1]){
                    start3++;
                }
                if(sortedTop50Results[0]<=sortedHeuScores[i]+sortedDifferences[(int) (sortedDifferences.length*0.9)]){
                    start4++;
                }
                if(sortedTop50Results[0]<=sortedHeuScores[i]+medianDifference){
                    start5++;
                }
                if(sortedTop50Results[0]<=sortedHeuScores[i]*medianPortion){
                    start6++;
                }
                if(sortedTop50Results[0]<=sortedHeuScores[i]*sortedPortions[(int) (sortedPortions.length*0.9)]){
                    start7++;
                }
                if(sortedTop50Results[0]<=sortedHeuScores[i]*sortedPortions[(sortedPortions.length-1)]){
                    start8++;
                }

                else {
                    break;
                }

            }
            output.subsearch1=resultList.size()+start1-1;
            output.subsearch2=resultList.size()+start2-1;
            output.subsearch3=resultList.size()+start3-1;
            output.subsearch4=resultList.size()+start4-1;
            output.subsearch5=resultList.size()+start5-1;
            output.subsearch5=resultList.size()+start6-1;
            output.subsearch5=resultList.size()+start7-1;
            output.subsearch5=resultList.size()+start8-1;

            if(sortedHeuScores.length>50){
//            start again with extra values
//                System.out.println("start2: " + Integer.toString(start2));
                int thresh = 0;
                (new Thread(new ConsumeGraphExact(Arrays.copyOfRange(sortedHeuScores,0,sortedHeuScores.length-50), thresh))).start();

                while (futureRes.size() < 1) {
                    Thread.sleep(5);
                }
                while (futureRes.size() > 0) {
                    Future<Response> future = futureRes.poll();
                    Response response = future.get();
                    presortedExScores[response.index-resultList.size()] = response.result;
                    predictedScores[response.index-resultList.size()] = response.prediction;
                }

            }
        }


        for (int j = presortedExScores.length - 1; j >= 0; j--) {

            if (presortedExScores[j] != null) {
                if (presortedExScores[j] > maxValue) {
                    maxValue = presortedExScores[j];
                    maxPosition = j;
                }
            } else {
                break;
            }
        }

        int position = presortedExScores.length - 1 - maxPosition;
        float cut = ((float) (maxPosition + 1) / (presortedExScores.length));

        output.cut = cut;
        output.result = maxValue;
        output.prediction = predictedScores[maxPosition];
        output.index = position;
        output.maxPrediction = sortedHeuScores[sortedHeuScores.length - 1];
        output.length = presortedExScores.length;
        return output;
    }





    /*
        some helper functions you might need
     */

    private double getScore(FTree tree) {
        return tree.getAnnotationOrThrow(TreeScoring.class).getOverallScore();
    }

    private FTree computeTreeExactly(FGraph graph) {
        FTree test = analyzer.computeTree(graph);
        return test;
    }

    private FGraph computeGraph(MolecularFormula formula) {
        return analyzer.buildGraph(input, formulasToConsider.get(formula));
    }

    private FTree computeTreeHeuristically(FGraph graph) {
        FTree tree = new CriticalPathSolver(graph).solve();
        analyzer.addTreeAnnotations(graph, tree);
        return tree;
    }


}
