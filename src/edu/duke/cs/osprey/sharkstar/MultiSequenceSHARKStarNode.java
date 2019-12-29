package edu.duke.cs.osprey.sharkstar;

import edu.duke.cs.osprey.astar.conf.ConfAStarNode;
import edu.duke.cs.osprey.astar.conf.ConfIndex;
import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.confspace.RCTuple;
import edu.duke.cs.osprey.confspace.Sequence;
import edu.duke.cs.osprey.confspace.SimpleConfSpace;
import edu.duke.cs.osprey.kstar.pfunc.BoltzmannCalculator;
import edu.duke.cs.osprey.kstar.pfunc.PartitionFunction;
import edu.duke.cs.osprey.markstar.prototype.SimpleConf;
import edu.duke.cs.osprey.tools.ExpFunction;
import edu.duke.cs.osprey.tools.MathTools;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;
import java.util.Map;

import static edu.duke.cs.osprey.sharkstar.MultiSequenceSHARKStarBound.confMatch;
import static edu.duke.cs.osprey.sharkstar.MultiSequenceSHARKStarBound.isDebugConf;
import static edu.duke.cs.osprey.sharkstar.tools.MultiSequenceSHARKStarNodeStatistics.*;


public class MultiSequenceSHARKStarNode implements Comparable<MultiSequenceSHARKStarNode> {


    static boolean debug = true;
    private boolean updated = true;

    private BigDecimal errorBound = BigDecimal.ONE;
    private double nodeEpsilon = 1;
    private MultiSequenceSHARKStarNode parent;
    private List<MultiSequenceSHARKStarNode> children;
    private Node confSearchNode;
    private SimpleConfSpace fullConfSpace;
    public final int level;
    private static BoltzmannCalculator bc = new BoltzmannCalculator(PartitionFunction.decimalPrecision);
    private final SimpleConfSpace.Position designPosition;
    private final SimpleConfSpace.Position nextDesignPosition;

    // Information for MultiSequence SHARK* Nodes
    private Map<Sequence, MathTools.BigDecimalBounds> sequenceBounds = new HashMap<>();
    private Map<String, List<MultiSequenceSHARKStarNode>> childrenByAA= new HashMap<>(); // probably should override the children list
    private Map<Sequence, List<MultiSequenceSHARKStarNode>> childrenBySequence = new HashMap<>(); // probably should override the children list
    private Map<Sequence, MathTools.DoubleBounds> confBounds = new HashMap<>();

    // Debugging variables
    private Map<Sequence, MathTools.BigDecimalBounds> lastSequenceBounds = new HashMap<>();
    private double lastEpsilon = nodeEpsilon;

    private MultiSequenceSHARKStarNode(Node confNode, MultiSequenceSHARKStarNode parent, SimpleConfSpace fullConfSpace,
                                       SimpleConfSpace.Position designPosition, SimpleConfSpace.Position nextDesignPosition){
        this.fullConfSpace = fullConfSpace;
        confSearchNode = confNode;
        this.level = confSearchNode.getLevel();
        this.children = new ArrayList<>();
        this.parent = parent;
        this.designPosition = designPosition;
        this.nextDesignPosition = nextDesignPosition;
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
        int[] newAssignments = new int[size];
        Arrays.fill(newAssignments, -1);
        for (int i =0; i < this.getConfSearchNode().assignments.length; i++){
            newAssignments[permutation[i]] = this.getConfSearchNode().assignments[i];
        }
        // Now I'm going to be hacky and just copy over the assignments
        this.getConfSearchNode().assignments = newAssignments;
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

    private void setSubtreeBounds(Sequence seq, BigDecimal lower, BigDecimal upper) {
        double tolerance = 0.01;
        if(MathTools.isRelativelySame(getSequenceBounds(seq).lower, lower, PartitionFunction.decimalPrecision, tolerance)
            && MathTools.isRelativelySame(getSequenceBounds(seq).upper, upper, PartitionFunction.decimalPrecision, tolerance))
            return;
        getLastSequenceBounds(seq).upper = getSequenceBounds(seq).upper;
        getLastSequenceBounds(seq).lower = getSequenceBounds(seq).lower;
        getSequenceBounds(seq).upper = upper;
        getSequenceBounds(seq).lower = lower;
        //System.out.println(toSeqString(seq)+String.format(", previously [%12.4e,%12.4e]",getLastSequenceBounds(seq).lower.doubleValue(), getLastSequenceBounds(seq).upper.doubleValue()));
        if(isDebugConf(confSearchNode.assignments)) {
            System.out.println("Updating "+toSeqString(seq));
            System.out.println(toSeqString(seq)+String.format(", previously [%12.4e,%12.4e]",getLastSequenceBounds(seq).lower.doubleValue(), getLastSequenceBounds(seq).upper.doubleValue()));
        }
        debugChecks(seq);
    }

    public void updateSubtreeBounds(Sequence seq) {
        List<MultiSequenceSHARKStarNode> childrenForSequence = getChildren(seq);
        if(childrenForSequence != null && childrenForSequence.size() > 0) {
            BigDecimal errorUpperBound = BigDecimal.ZERO;
            BigDecimal errorLowerBound = BigDecimal.ZERO;
            for(MultiSequenceSHARKStarNode child: childrenForSequence) {
                child.updateSubtreeBounds(seq);
                errorUpperBound = errorUpperBound.add(child.getSequenceBounds(seq).upper);
                errorLowerBound = errorLowerBound.add(child.getSequenceBounds(seq).lower);
            }
            setSubtreeBounds(seq, errorLowerBound, errorUpperBound);
        }
    }

    private double computeEpsilon(MathTools.BigDecimalBounds bounds) {
        if (bounds.upper.subtract(bounds.lower).compareTo(BigDecimal.ONE) < 1) {
            return 0;
        } else {
            return bounds.upper.subtract(bounds.lower)
                    .divide(bounds.upper, RoundingMode.HALF_UP).doubleValue();
        }
    }

    public double computeEpsilonErrorBounds(Sequence seq) {
        if(children == null || children.size() <1) {
            return nodeEpsilon;
        }
        if(!updated)
            return nodeEpsilon;
        double epsilonBound = 0;
        updateSubtreeBounds(seq);
        if(MathTools.isLessThan(getUpperBound(seq), getLowerBound(seq)))
        {
            return 0;
        }
        if(level == 0) {
            epsilonBound = computeEpsilon(getSequenceBounds(seq));
            debugChecks(seq);
            lastEpsilon = nodeEpsilon;
            nodeEpsilon = epsilonBound;
            if(debug && false)
                printBoundBreakDown(seq, this);
        }
        return nodeEpsilon;
    }


    private void debugChecks(Sequence seq) {
        debugChecks(seq, false);
    }

    private void debugChecks(Sequence seq, boolean showTree) {
        if (!debug)
            return;
        if(showTree && MathTools.isGreaterThan(getUpperBound(seq), new BigDecimal(10)))
            System.out.println(toSeqString(seq)+String.format(", previously [%12.4e,%12.4e]",getLastSequenceBounds(seq).lower.doubleValue(), getLastSequenceBounds(seq).upper.doubleValue()));
        BigDecimal tolerance = new BigDecimal(0.0001);
        BigDecimal lastUpper = getLastSequenceBounds(seq).upper;
        BigDecimal lastLower = getLastSequenceBounds(seq).lower;
        if(lastUpper != null
                && MathTools.isGreaterThan(getSequenceBounds(seq).upper,lastUpper)
                && getSequenceBounds(seq).upper.subtract(lastUpper).compareTo(BigDecimal.TEN) > 0
                && getSequenceBounds(seq).upper.subtract(lastUpper).compareTo(tolerance.multiply(lastUpper)) > 0) {
            System.err.println("Upper bound got bigger!?");
            System.err.println("Previous: "+setSigFigs(lastUpper)+", now "+setSigFigs(getSequenceBounds(seq).upper));
            System.err.println("Increased by "+getSequenceBounds(seq).upper.subtract(lastUpper));
            System.out.println("Current Tree:");
            printTree(seq, this);
            System.out.println("Last Tree:");
            printLastTree(seq, this);
            System.exit(-1);
        }
        if(lastLower != null
                && MathTools.isLessThan(getSequenceBounds(seq).lower,lastLower)
                && getSequenceBounds(seq).lower.subtract(lastLower).compareTo(BigDecimal.TEN) > 0
                && lastLower.subtract(getSequenceBounds(seq).lower).compareTo(tolerance.multiply(lastLower)) > 0) {
            System.err.println("Lower bound got smaller!?");
            System.err.println("Decreased by "+lastLower.subtract(getSequenceBounds(seq).lower));
            System.out.println("Current Tree:");
            printTree(seq, this);
            System.out.println("Last Tree:");
            printLastTree(seq, this);
            System.exit(-1);
        }
        /* this check doesn't work across multiple sequences yet.
        if(lastEpsilon < nodeEpsilon && nodeEpsilon - lastEpsilon > 0.0001) {
            System.err.println("Epsilon got bigger. Error.");
            System.err.println("UpperBound change: "+getSequenceBounds(seq).upper.subtract(lastUpper));
            System.err.println("LowerBound change: "+getSequenceBounds(seq).lower.subtract(lastLower));
        }
        */

    }

    public BigDecimal getUpperBound(Sequence seq){
        return getSequenceBounds(seq).upper;
    }

    public BigDecimal getLowerBound(Sequence seq){
        return getSequenceBounds(seq).lower;
    }

    public void index(ConfIndex confIndex) {
        confSearchNode.index(confIndex);
    }

    public Node getConfSearchNode() {
        return confSearchNode;
    }

    public MultiSequenceSHARKStarNode makeChild(Node child, Sequence seq, double lowerBound, double upperBound,
                                                SimpleConfSpace.Position designPosition,
                                                SimpleConfSpace.Position nextDesignPosition) {
        checkChildren(seq);
        for(MultiSequenceSHARKStarNode childCheck: getChildren(seq)) {
            if(childCheck.getConfSearchNode().rc == child.rc) {
                System.err.println("Should not be making a dupe node.");
            }
        }
        MultiSequenceSHARKStarNode newChild = new MultiSequenceSHARKStarNode(child, this, this.fullConfSpace,
                designPosition, nextDesignPosition);
        newChild.computeEpsilonErrorBounds(seq);
        getChildren(seq).add(newChild);
        children.add(newChild);
        newChild.setBoundsFromConfLowerAndUpper(lowerBound, upperBound, seq);
        newChild.errorBound = getErrorBound(seq);
        checkChildren(seq);
        return newChild;
    }

    public void setBoundsFromConfLowerAndUpper(double lowerBound, double upperBound, Sequence seq) {
        MathTools.BigDecimalBounds bounds = getSequenceBounds(seq);
        BigDecimal subtreeLowerBound = bounds.lower;
        BigDecimal subtreeUpperBound = bounds.upper;
        BigDecimal tighterLower = bc.calc(upperBound);
        BigDecimal tighterUpper = bc.calc(lowerBound);
        if (subtreeLowerBound != null && MathTools.isGreaterThan(subtreeLowerBound, tighterLower))
            System.err.println("Updating subtree lower bound " + convertMagicBigDecimalToString(subtreeLowerBound)
                    + " with " + tighterLower + ", which is lower!?");
        if (subtreeUpperBound != null && MathTools.isLessThan(subtreeUpperBound, tighterUpper))
            System.err.println("Updating subtree upper bound " + convertMagicBigDecimalToString(subtreeUpperBound)
                    + " with " + convertMagicBigDecimalToString(tighterUpper) + ", which is greater!?");
        setSubtreeBounds(seq, tighterLower, tighterUpper);
        getSequenceConfBounds(seq).lower = lowerBound;
        getSequenceConfBounds(seq).upper = upperBound;
    }

    private MathTools.DoubleBounds getSequenceConfBounds(Sequence seq) {
        if(!confBounds.containsKey(seq)) {
            confBounds.put(seq, new MathTools.DoubleBounds(Double.MIN_VALUE, Double.MAX_VALUE));
        }
        return confBounds.get(seq);
    }

    public List<MultiSequenceSHARKStarNode> getChildren(Sequence seq) {
        if(seq == null)
            return children;
        initChildren(seq);
        if(debug)
            checkChildren(seq);
        return childrenBySequence.get(seq);
    }

    private String getAllowedAA(Sequence seq) {
        String resNum = nextDesignPosition.resNum;
        String AA = nextDesignPosition.resTypes.get(0);
        if(seq.seqSpace.getResNums().contains(resNum))
            AA = seq.get(resNum).name;
        return AA;
    }

    private void initChildren(Sequence seq) {
        if(!childrenBySequence.containsKey(seq)) {
            if (level >= fullConfSpace.positions.size()-1) {
                childrenBySequence.put(seq, new ArrayList<>());
                return;
            }
            String AA = getAllowedAA(seq);
            if (!childrenByAA.containsKey(AA) || childrenByAA.get(AA).isEmpty())
                childrenByAA.put(AA, populateChildren(seq));
            childrenBySequence.put(seq, childrenByAA.get(AA));
        }
    }

    private void checkChildren(Sequence seq) {
        initChildren(seq);
        Set<Integer> rcs = new HashSet<>();
        List<MultiSequenceSHARKStarNode> multiSequenceSHARKStarNodes = childrenBySequence.get(seq);
        for(MultiSequenceSHARKStarNode node: multiSequenceSHARKStarNodes) {
            int rc = node.confSearchNode.assignments[node.confSearchNode.pos];
            if(rcs.contains(rc)) {
                System.out.println("Dupe nodes for "+seq+":");
                for(MultiSequenceSHARKStarNode nodecheck: multiSequenceSHARKStarNodes) {
                    if(nodecheck.confSearchNode.assignments[node.confSearchNode.pos] == rc)
                        System.out.println(nodecheck+"-"+nodecheck.confSearchNode.confToString());
                }
            }
            rcs.add(rc);
        }
    }

    private List<MultiSequenceSHARKStarNode> populateChildren(Sequence seq) {
        List<MultiSequenceSHARKStarNode> childrenForSeq = new ArrayList<>();
        Set<Integer> rcs = new HashSet<>();
        RCs seqRCs =  seq.makeRCs(fullConfSpace);
        //int maxChildren = seqRCs.get(confSearchNode.pos+1).length;
        for (MultiSequenceSHARKStarNode child: children) {
            if (child.designPosition.equals(nextDesignPosition) &&
                    Arrays.stream(seqRCs.get(child.confSearchNode.pos)).anyMatch(i -> i == child.confSearchNode.rc)) {
                childrenForSeq.add(child);
                rcs.add(child.confSearchNode.rc);
            }
        }
        return childrenForSeq;
    }

    private RCs RCsForResType(String seq, SimpleConfSpace.Position qpos) {
        return new RCs(fullConfSpace, (pos, resConf) -> seq.equals(resConf.template.name) && qpos.index == pos.index);
    }

    public boolean isLeaf() {
        return isLeaf(null);
    }

    public boolean isLeaf(Sequence seq) {
        if(seq == null)
            return children == null || children.size() < 1;
        return getChildren(seq) == null || getChildren(seq).size() < 1;
    }

    public void setNewConfSpace(SimpleConfSpace confSpace) {
        if(fullConfSpace == confSpace) return;
        fullConfSpace = confSpace;
        for(MultiSequenceSHARKStarNode child: children)
            child.setNewConfSpace(confSpace);
    }

    public double getConfLowerBound(Sequence seq) {
        return getSequenceConfBounds(seq).lower;
    }

    public double getConfUpperBound(Sequence seq) {
        return getSequenceConfBounds(seq).upper;
    }

    public static enum Type {
        internal,
        boundedLeaf,
        minimizedLeaf
    }

    public Type type(SingleSequenceSHARKStarBound bound) {
        if(level < bound.seqRCs.getNumPos())
            return  Type.internal;
        if(isMinimized(bound.sequence))
            return Type.boundedLeaf;
        return Type.minimizedLeaf;
    }

    public static MultiSequenceSHARKStarNode makeRoot(Node rootNode, SimpleConfSpace fullConfSpace,
                                                      SimpleConfSpace.Position nextDesignPosition) {
        return new MultiSequenceSHARKStarNode(rootNode, null, fullConfSpace, null,
                nextDesignPosition);
    }


    @Override
    public int compareTo(MultiSequenceSHARKStarNode other){
        throw new UnsupportedOperationException("You can't compare multisequence nodes without a sequence.");
    }

    public BigDecimal getErrorBound(Sequence seq) {
        if(isMinimized(seq))
            return BigDecimal.ZERO;
        if(getChildren(seq) == null || getChildren(seq).size() < 1) {
            MathTools.BigDecimalBounds seqBounds = getSequenceBounds(seq);
            if(MathTools.isFinite(seqBounds.lower) && MathTools.isFinite(seqBounds.upper))
                return  getSequenceBounds(seq).upper.subtract(getSequenceBounds(seq).lower);
            else return null;
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
            sequenceBounds.put(seq, new MathTools.BigDecimalBounds(BigDecimal.ZERO, MathTools.BigPositiveInfinity));
        }
        return sequenceBounds.get(seq);
    }
    public MathTools.BigDecimalBounds getLastSequenceBounds(Sequence seq) {
        if(!lastSequenceBounds.containsKey(seq)) {
            lastSequenceBounds.put(seq, new MathTools.BigDecimalBounds(BigDecimal.ZERO, MathTools.BigPositiveInfinity));
        }
        return lastSequenceBounds.get(seq);
    }

    public void debugTree(Sequence seq) {
        debugChecks(seq, false);
        if(!getChildren(seq).isEmpty()){
            for(MultiSequenceSHARKStarNode child: getChildren(seq))
                child.debugTree(seq);
        }
    }


    public String toFancySeqString(Sequence seq) {
        String out = fullConfSpace.formatConfRotamersWithResidueNumbers(confSearchNode.assignments);
        BigDecimal subtreeLowerBound = getLowerBound(seq);
        BigDecimal subtreeUpperBound = getUpperBound(seq);
        out += "Energy:" + String.format("%4.2f", confSearchNode.getPartialConfLowerBound()) + "*" + confSearchNode.numConfs;
        if (!isMinimized(seq))
            out += " in [" + String.format("%4.4e,%4.4e", getSequenceConfBounds(seq).lower, getSequenceConfBounds(seq).upper)
                    + "]->[" + setSigFigs(subtreeLowerBound) + "," + setSigFigs(subtreeUpperBound) + "]";
        else
            out += " (minimized) -> " + setSigFigs(subtreeLowerBound);
        return out;
    }

    public String toSeqString(Sequence seq) {
        String out = confSearchNode.confToString();//fullConfSpace.formatConfRotamersWithResidueNumbers(confSearchNode.assignments);//
        BigDecimal subtreeLowerBound = getLowerBound(seq);
        BigDecimal subtreeUpperBound = getUpperBound(seq);
        out += "Energy:" + String.format("%4.2f", confSearchNode.getPartialConfLowerBound()) + "*" + confSearchNode.numConfs;
        if (!isMinimized(seq))
            out += " in [" + String.format("%4.4e,%4.4e", getSequenceConfBounds(seq).lower, getSequenceConfBounds(seq).upper)
                    + "]->[" + setSigFigs(subtreeLowerBound) + "," + setSigFigs(subtreeUpperBound) + "]";
        else
            out += " (minimized) -> " + setSigFigs(subtreeLowerBound);
        return out;
    }

    public boolean isMinimized(Sequence seq) {
        double confLowerBound = getSequenceConfBounds(seq).lower;
        double confUpperBound = getSequenceConfBounds(seq).upper;
        return Math.abs(confLowerBound - confUpperBound) < 1e-5;
    }

    public static class Node implements ConfAStarNode {

        private static int Unassigned = -1;
        private double partialConfLowerBound = Double.NaN;
        private double partialConfUpperBound = Double.NaN;
        private double confLowerBound = Double.MAX_VALUE;
        private double confUpperBound = Double.MIN_VALUE;
        public int[] assignments;
        public int pos = Unassigned;
        public int rc = Unassigned;
        public final int level;
        public BigInteger numConfs = BigInteger.ZERO;

        public Node(int size, int level) {
            this(size, level, new MathTools.DoubleBounds());
        }
        public Node(int size, MathTools.DoubleBounds fullConfBounds) {
            this(size, 0, fullConfBounds);
        }

        public Node(int size, int level, MathTools.DoubleBounds fullConfBounds) {
            assignments = new int[size];
            Arrays.fill(assignments, Unassigned);
            this.level = level;
            confLowerBound = fullConfBounds.lower;
            confUpperBound = fullConfBounds.upper;
            if(this.level == 0) {
                partialConfUpperBound = 0;
                partialConfLowerBound = 0;
            }
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
            if (tighterUpper < confUpperBound) {
                confUpperBound = tighterUpper;
            }
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

        @Override
        public void getConf(int[] conf) {
            System.arraycopy(assignments, 0, conf, 0, assignments.length);
        }

        @Override
        public double getGScore() {
            return getPartialConfLowerBound();
        }

        @Override
        public double getRigidGScore() {
            return getPartialConfUpperBound();
        }

        @Override
        public void setGScore(double val) {
            throw new UnsupportedOperationException("Should not be set this way.");
        }

        @Override
        public double getHScore() {
            return confLowerBound - getPartialConfLowerBound();
        }

        @Override
        public void setHScore(double val) {
            throw new UnsupportedOperationException("Should not be set this way.");
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
            confIndex.node = this;
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

        public double getPartialConfLowerBound() {
            return partialConfLowerBound;
        }

        public void setPartialConfLowerAndUpper(double partialConfLowerBound, double partialConfUpperBound) {
            this.partialConfLowerBound = partialConfLowerBound;
            if(partialConfUpperBound == 0)
                System.err.println("Wtf?");
            this.partialConfUpperBound = partialConfUpperBound;
        }

        public double getPartialConfUpperBound() {
            return partialConfUpperBound;
        }

        public void setBoundsFromConfLowerAndUpper(double lowerBound, double upperBound) {
            if(level == assignments.length) {
                partialConfLowerBound = lowerBound;
                partialConfUpperBound = upperBound;
            }
            if(upperBound == Double.NaN)
                System.err.println("????");
            if (lowerBound - upperBound > 1e-5) {
                if (debug)
                    System.out.println("Incorrect conf bounds set.");
                double temp = lowerBound;
                lowerBound = upperBound;
                upperBound = temp;
                lowerBound = Math.min(0, lowerBound);
                upperBound = Math.max(lowerBound, upperBound);
            }
            //updateConfLowerBound(lowerBound);
            //updateConfUpperBound(upperBound);
        }
    }
}
