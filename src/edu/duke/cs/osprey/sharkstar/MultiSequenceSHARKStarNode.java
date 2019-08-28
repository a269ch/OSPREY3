package edu.duke.cs.osprey.sharkstar;

import edu.duke.cs.osprey.astar.conf.ConfIndex;
import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.confspace.RCTuple;
import edu.duke.cs.osprey.confspace.Sequence;
import edu.duke.cs.osprey.confspace.SimpleConfSpace;
import edu.duke.cs.osprey.kstar.pfunc.BoltzmannCalculator;
import edu.duke.cs.osprey.kstar.pfunc.PartitionFunction;
import edu.duke.cs.osprey.tools.ExpFunction;
import edu.duke.cs.osprey.tools.MathTools;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;
import java.util.Map;

import static edu.duke.cs.osprey.sharkstar.tools.MultiSequenceSHARKStarNodeStatistics.printBoundBreakDown;
import static edu.duke.cs.osprey.sharkstar.tools.MultiSequenceSHARKStarNodeStatistics.setSigFigs;


public class MultiSequenceSHARKStarNode implements Comparable<MultiSequenceSHARKStarNode> {


    static boolean debug = false;
    private boolean updated = true;
    /**
     * TODO: 1. Make MARKStarNodes spawn their own Node and MARKStarNode children.
     * TODO: 2. Make MARKStarNodes compute and update bounds correctly
     */

    private BigDecimal errorBound = BigDecimal.ONE;
    private double nodeEpsilon = 1;
    private MultiSequenceSHARKStarNode parent;
    private List<MultiSequenceSHARKStarNode> children; // TODO: Pick appropriate data structure
    private Node confSearchNode;
    public final int level;
    private static ExpFunction ef = new ExpFunction();
    private static BoltzmannCalculator bc = new BoltzmannCalculator(PartitionFunction.decimalPrecision);
    private boolean partOfLastBound = false;

    // Information for MultiSequence SHARK* Nodes
    private Map<Sequence, MathTools.BigDecimalBounds> sequenceBounds = new HashMap<>();
    private Map<Sequence, List<MultiSequenceSHARKStarNode>> childrenMap = new HashMap<>(); // probably should override the children list


    private MultiSequenceSHARKStarNode(Node confNode, MultiSequenceSHARKStarNode parent){
        confSearchNode = confNode;
        this.level = confSearchNode.getLevel();
        this.children = new ArrayList<>();
        this.parent = parent;
    }

    /**
     * Makes a SHARKStarNode generated during flexible precomputation compatible with a new conformation space
     *
     * @param permutation   The permutation array that maps the precomputed flexible positions to the positions in the new confSpace
     *                      so, permutation[i] gives the new ConfSpace index for residue i.
     * @param size  The size of the new confSpace
     */
    public void makeNodeCompatibleWithConfSpace(int[] permutation, int size, RCs RCs){
        // first change the confSearch information

        // we copy over the new RCs based on permutation information
        Node newNode = new Node(size, this.level);
        for (int i =0; i < this.getConfSearchNode().assignments.length; i++){
            newNode.assignments[permutation[i]] = this.getConfSearchNode().assignments[i];
        }
        // Now I'm going to be hacky and just copy over the assignments
        this.getConfSearchNode().assignments = newNode.assignments;
        if (this.getConfSearchNode().pos != -1){
            this.getConfSearchNode().pos = permutation[this.getConfSearchNode().pos];
        }

        // Compute the number of conformations
        this.getConfSearchNode().computeNumConformations(RCs);
        this.updated = true;
    }

    public BigInteger getNumConfs()
    {
        return confSearchNode.numConfs;
    }

    public void markUpdated()
    {
        updated = true;
        if(level > 0)
            parent.markUpdated();
    }

    public RCTuple toTuple() {
        return new RCTuple(confSearchNode.assignments);
    }

    public BigDecimal getErrorBound(){
        return errorBound;
    }

    public void updateSubtreeBounds(Sequence seq) {
        List<MultiSequenceSHARKStarNode> childrenForSequence = getChildren(seq);
        if(!updated)
            return;
        updated = false;
        if(childrenForSequence != null && childrenForSequence.size() > 0) {
            BigDecimal errorUpperBound = BigDecimal.ZERO;
            BigDecimal errorLowerBound = BigDecimal.ZERO;
            for(MultiSequenceSHARKStarNode child: childrenForSequence) {
                child.updateSubtreeBounds(seq);
                errorUpperBound = errorUpperBound.add(child.getSequenceBounds(seq).upper);
                errorLowerBound = errorLowerBound.add(child.getSequenceBounds(seq).lower);
            }
            getSequenceBounds(seq).upper = errorUpperBound;
            getSequenceBounds(seq).lower = errorLowerBound;
        }
    }

    private double computeEpsilon(MathTools.BigDecimalBounds bounds) {
        return bounds.upper.subtract(bounds.lower)
                .divide(bounds.upper, RoundingMode.HALF_UP).doubleValue();
    }

    public double computeEpsilonErrorBounds(Sequence seq) {
        if(children == null || children.size() <1) {
            return nodeEpsilon;
        }
        if(!updated)
            return nodeEpsilon;
        double epsilonBound = 0;
        BigDecimal lastUpper = getSequenceBounds(seq).upper;
        BigDecimal lastLower = getSequenceBounds(seq).lower;
        updateSubtreeBounds(seq);
        if(getSequenceBounds(seq).upper.subtract(getSequenceBounds(seq).lower).compareTo(BigDecimal.ONE)<1)
        {
            return 0;
        }
        if(level == 0) {
            epsilonBound = computeEpsilon(getSequenceBounds(seq));
            debugChecks(lastUpper, lastLower, epsilonBound, seq);
            nodeEpsilon = epsilonBound;
            if(debug)
                printBoundBreakDown(seq, this);
        }
        return nodeEpsilon;
    }

    private void debugChecks(BigDecimal lastUpper, BigDecimal lastLower, double epsilonBound, Sequence seq) {
        if (!debug)
            return;
        BigDecimal tolerance = new BigDecimal(0.00001);
        if(lastUpper != null
                && getSequenceBounds(seq).upper.subtract(lastUpper).compareTo(BigDecimal.ZERO) > 0
                && getSequenceBounds(seq).upper.subtract(lastUpper).compareTo(tolerance) > 0) {
            System.err.println("Upper bound got bigger!?");
            System.err.println("Previous: "+setSigFigs(lastUpper)+", now "+setSigFigs(getSequenceBounds(seq).upper));
            System.err.println("Increased by "+lastUpper.subtract(getSequenceBounds(seq).upper));
        }
        if(lastLower != null
                && getSequenceBounds(seq).lower.subtract(lastLower).compareTo(BigDecimal.ZERO) < 0
                && lastLower.subtract(getSequenceBounds(seq).lower).compareTo(tolerance) > 0) {
            System.err.println("Lower bound got smaller!?");
            System.err.println("Decreased by "+lastLower.subtract(getSequenceBounds(seq).lower));
        }
        if(nodeEpsilon < epsilonBound && epsilonBound - nodeEpsilon > 0.0001) {
            System.err.println("Epsilon got bigger. Error.");
            System.err.println("UpperBound change: "+getSequenceBounds(seq).upper.subtract(lastUpper));
            System.err.println("LowerBound change: "+getSequenceBounds(seq).lower.subtract(lastLower));
        }

    }

    public BigDecimal getUpperBound(Sequence seq){
        return getSequenceBounds(seq).upper;
    }

    public BigDecimal getLowerBound(Sequence seq){
        return getSequenceBounds(seq).lower;
    }

    public double getConfLowerbound() {
        return confSearchNode.confLowerBound;
    }

    public double getConfUpperbound() {
        return confSearchNode.confUpperBound;
    }


    public void index(ConfIndex confIndex) {
        confSearchNode.index(confIndex);
    }

    public Node getConfSearchNode() {
        return confSearchNode;
    }

    public MultiSequenceSHARKStarNode makeChild(Node child, Sequence seq) {
        MultiSequenceSHARKStarNode newChild = new MultiSequenceSHARKStarNode(child, this);
        newChild.computeEpsilonErrorBounds(seq);
        newChild.errorBound = getErrorBound(seq);
        getChildren(seq).add(newChild);
        children.add(newChild);
        return newChild;
    }

    public void setBoundsFromConfLowerAndUpper(double lowerBound, double upperBound, Sequence seq) {
        MathTools.BigDecimalBounds bounds = getSequenceBounds(seq);
        BigDecimal subtreeLowerBound = bounds.lower;
        BigDecimal subtreeUpperBound = bounds.upper;
        BigDecimal tighterLower = bc.calc(lowerBound);
        BigDecimal tighterUpper = bc.calc(upperBound);
        if (subtreeLowerBound != null && subtreeLowerBound.compareTo(tighterLower) > 0)
            System.err.println("Updating subtree lower bound " + setSigFigs(subtreeLowerBound)
                    + " with " + tighterLower + ", which is lower!?");
        if (subtreeUpperBound != null && subtreeUpperBound.compareTo(tighterUpper) < 0)
            System.err.println("Updating subtree upper bound " + setSigFigs(subtreeUpperBound)
                    + " with " + setSigFigs(tighterUpper) + ", which is greater!?");
        getSequenceBounds(seq).lower = bc.calc(lowerBound);
        getSequenceBounds(seq).upper = bc.calc(upperBound);
        /* old behavior. replaced with new behavior that doesn't put subtree bounds in confSearchNodes.
        confSearchNode.updateConfLowerBound(lowerBound);
        confSearchNode.updateConfUpperBound(upperBound);
        confSearchNode.setBoundsFromConfLowerAndUpper(lowerBound, upperBound);
         */
    }

    public List<MultiSequenceSHARKStarNode> getChildren() {
        return getChildren(null);
    }

    public List<MultiSequenceSHARKStarNode> getChildren(Sequence seq) {
        if(seq == null)
            return children;
        if(!childrenMap.containsKey(seq))
            childrenMap.put(seq, new ArrayList<>());
        return childrenMap.get(seq);
    }

    public boolean isLeaf() {
        return isLeaf(null);
    }

    public boolean isLeaf(Sequence seq) {
        if(seq == null)
            return children == null || children.size() < 1;
        return getChildren(seq) == null || getChildren(seq).size() < 1;
    }

    public static enum Type {
        internal,
        boundedLeaf,
        minimizedLeaf
    }

    public Type type(RCs rcs) {
        if(level < rcs.getNumPos())
            return  Type.internal;
        if(!confSearchNode.isMinimized())
            return Type.boundedLeaf;
        return Type.minimizedLeaf;
    }

    public static MultiSequenceSHARKStarNode makeRoot(Node rootNode) {
        return new MultiSequenceSHARKStarNode(rootNode, null);
    }


    @Override
    public int compareTo(MultiSequenceSHARKStarNode other){
        throw new UnsupportedOperationException("You can't compare multisequence nodes without a sequence.");
    }

    public BigDecimal getErrorBound(Sequence seq) {
        if(confSearchNode.isMinimized())
            return BigDecimal.ZERO;
        if(getChildren(seq) == null || getChildren(seq).size() < 1) {
            return  getSequenceBounds(seq).upper.subtract(getSequenceBounds(seq).lower);
        }
        BigDecimal errorSum = BigDecimal.ZERO;
        for(MultiSequenceSHARKStarNode childNode: getChildren(seq)) {
            errorSum = errorSum.add(childNode.getErrorBound(seq));
        }
        errorBound = errorSum;
        return errorBound;
    }

    public MathTools.BigDecimalBounds getSequenceBounds(Sequence seq) {
        if(!sequenceBounds.containsKey(seq)) {
            sequenceBounds.put(seq, new MathTools.BigDecimalBounds());
        }
        return sequenceBounds.get(seq);
    }

    public String toSeqString(Sequence seq) {
        String out = confSearchNode.confToString();
        BigDecimal subtreeLowerBound = getLowerBound(seq);
        BigDecimal subtreeUpperBound = getUpperBound(seq);
        out += "Energy:" + String.format("%4.2f", confSearchNode.partialConfLowerbound) + "*" + confSearchNode.numConfs;
        if (!confSearchNode.isMinimized())
            out += " in [" + String.format("%4.4e,%4.4e", confSearchNode.confLowerBound, confSearchNode.confUpperBound)
                    + "]->[" + setSigFigs(subtreeLowerBound) + "," + setSigFigs(subtreeUpperBound) + "]";
        else
            out += " (minimized) -> " + setSigFigs(subtreeLowerBound);
        return out;
    }

    public static class Node {

        private static int Unassigned = -1;
        public double partialConfLowerbound = Double.NaN;
        public double partialConfUpperBound = Double.NaN;
        private BigDecimal subtreeLowerBound_deprecated = BigDecimal.ZERO; //\hat h^ominus(f) - the lower bound on subtree contrib to partition function
        private BigDecimal subtreeUpperBound_deprecated = MathTools.BigPositiveInfinity; //\hat h^oplus(f) - the lower bound on subtree contrib to partition function
        private double confLowerBound = -Double.MAX_VALUE;
        private double confUpperBound = Double.MAX_VALUE;
        public int[] assignments;
        public int pos = Unassigned;
        public int rc = Unassigned;
        public final int level;
        public BigInteger numConfs = BigInteger.ZERO;

        public Node(int size) {
            this(size, 0);
        }

        public Node(int size, int level) {
            assignments = new int[size];
            Arrays.fill(assignments, Unassigned);
            this.level = level;
        }

        public void setConfLowerBound(double confLowerBound) {
            this.confLowerBound = confLowerBound;
        }

        public void setConfUpperBound(double confUpperBound) {
            this.confUpperBound = confUpperBound;
        }

        public void setBoundsFromConfLowerAndUpper(double lowerBound, double upperBound) {
            if (lowerBound - upperBound > 1e-5) {
                if (debug)
                    System.out.println("Incorrect conf bounds set.");
                double temp = lowerBound;
                lowerBound = upperBound;
                upperBound = temp;
                lowerBound = Math.min(0, lowerBound);
                upperBound = Math.max(lowerBound, upperBound);
            }
            updateConfLowerBound(lowerBound);
            updateConfUpperBound(upperBound);
        }


        private void updateConfLowerBound(double tighterLower) {
            if (tighterLower < 10 && tighterLower - confLowerBound < -1e-5)
                System.err.println("Updating conf lower bound of  " + confLowerBound
                        + " with " + tighterLower + ", which is lower!?");
            if (tighterLower > confLowerBound) {
                confLowerBound = tighterLower;
            }
        }

        private void updateConfUpperBound(double tighterUpper) {
            if (tighterUpper < 10 && tighterUpper - confUpperBound > 1e-5)
                System.err.println("Updating conf upper bound of  " + confUpperBound
                        + " with " + tighterUpper + ", which is greater!?");
            if (tighterUpper == Double.POSITIVE_INFINITY)
                updateSubtreeLowerBound(BigDecimal.ZERO);
            if (tighterUpper < confUpperBound) {
                confUpperBound = tighterUpper;
            }
        }

        private void updateSubtreeLowerBound(BigDecimal tighterLower) {
        }

        private void updateSubtreeUpperBound(BigDecimal tighterUpper) {
        }

        public boolean isMinimized() {
            return Math.abs(confLowerBound - confUpperBound) < 1e-5;
        }

        public boolean isLeaf() {
            return level == assignments.length;
        }

        public Node assign(int pos, int rc) {
            Node node = new Node(assignments.length, level + 1);
            node.pos = pos;
            node.rc = rc;
            System.arraycopy(assignments, 0, node.assignments, 0, assignments.length);
            node.assignments[pos] = rc;
            return node;
        }

        public int getLevel() {
            return level;
        }

        public void index(ConfIndex confIndex) {
            confIndex.numDefined = 0;
            confIndex.numUndefined = 0;
            for (int pos = 0; pos < assignments.length; pos++) {
                int rc = assignments[pos];
                if (rc == -1) {
                    confIndex.undefinedPos[confIndex.numUndefined] = pos;
                    confIndex.numUndefined++;
                } else {
                    confIndex.definedPos[confIndex.numDefined] = pos;
                    confIndex.definedRCs[confIndex.numDefined] = assignments[pos];
                    confIndex.numDefined++;
                }
            }

            /* We never use this. Supporting it is unnecessary now. */
            confIndex.node = null;
        }

        public String confToString() {
            String out = "(";
            for (int i = 0; i < assignments.length; i++) {
                out += assignments[i] + ", ";
            }
            out += ")";
            return out;
        }


        public BigInteger getNumConformations() {
            return numConfs;
        }

        public void computeNumConformations(RCs rcs) {
            this.numConfs = rcs.getNumConformations();
            assert (this.numConfs.compareTo(BigInteger.ZERO) > 0);
        }

        public double getConfLowerBound() {
            return confLowerBound;
        }

        public double getConfUpperBound() {
            return confUpperBound;
        }
    }
}
