package edu.duke.cs.osprey.sharkstar;

import edu.duke.cs.osprey.astar.conf.ConfAStarNode;
import edu.duke.cs.osprey.astar.conf.ConfIndex;
import edu.duke.cs.osprey.astar.conf.PartialConfAStarNode;
import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.confspace.RCTuple;
import edu.duke.cs.osprey.confspace.Sequence;
import edu.duke.cs.osprey.confspace.SimpleConfSpace;
import edu.duke.cs.osprey.kstar.pfunc.BoltzmannCalculator;
import edu.duke.cs.osprey.kstar.pfunc.PartitionFunction;
import edu.duke.cs.osprey.tools.MathTools;
import jdk.nashorn.internal.runtime.regexp.joni.exception.ValueException;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.Map;

import static edu.duke.cs.osprey.sharkstar.MultiSequenceSHARKStarBound.isDebugConf;
import static edu.duke.cs.osprey.sharkstar.tools.MultiSequenceSHARKStarNodeStatistics.*;


public class MultiSequenceSHARKStarNode implements Comparable<MultiSequenceSHARKStarNode> {
    // statics
    static boolean debug = true;
    static BigDecimal bigDecimalBoundTolerance = BigDecimal.valueOf(1e-4);

    // MSSHARKSTAR Node stuff

    private final MultiSequenceSHARKStarNode parent;
    private final List<MultiSequenceSHARKStarNode> children;
    private final Node confSearchNode;
    public SimpleConfSpace.Position nextDesignPosition;
    public final int level;

    // Information for MultiSequence SHARK* Nodes
    private final Map<Sequence, BigDecimal> errorBounds = new HashMap<>();
    private final Map<Sequence, MathTools.BigDecimalBounds> sequenceBounds = new HashMap<>();
    private final Map<String, List<MultiSequenceSHARKStarNode>> childrenByAA= new HashMap<>(); // probably should override the children list
    private final Map<Sequence, List<MultiSequenceSHARKStarNode>> childrenBySequence = new HashMap<>(); // probably should override the children list
    private final Map<Sequence, MathTools.DoubleBounds> confBounds = new HashMap<>();
    private final Map<Integer,MultiSequenceSHARKStarNode> childrenByRC;

    // Debugging variables
    private Map<Sequence, List<String>> nodeHistory = null;

    MultiSequenceSHARKStarNode(Node confNode, MultiSequenceSHARKStarNode parent,
                               SimpleConfSpace.Position nextDesignPosition){
        this.confSearchNode = confNode;
        this.level = confSearchNode.getLevel();
        this.children = new ArrayList<>();
        this.parent = parent;
        this.nextDesignPosition = nextDesignPosition;
        this.childrenByRC = new HashMap<>();

        if(debug){
            nodeHistory = new HashMap<>();
        }
    }

    /**
     * Makes a SHARKStarNode generated during flexible precomputation compatible with a new conformation space
     *
     * @param permutation   The permutation array that maps the precomputed flexible positions to the positions in the new confSpace
     *                      so, permutation[i] gives the new ConfSpace index for residue i.
     * @param size  The size of the new confSpace
     */
    public void makeNodeCompatibleWithConfSpace(int[] permutation, int size){
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
    }

    public RCTuple toTuple() {
        return new RCTuple(confSearchNode.assignments);
    }

    /**
     * Set the node's partition-function bounds to a specified interval
     * @param bounds The bound interval
     * @param seq The sequence associated with the bounds
     * @param source A string describing the source of the bounds. Used for debug purposes
     */
    public synchronized void setPfuncBounds(MathTools.BigDecimalBounds bounds, Sequence seq, String source){
        if(debug) {
            // check the bounds for errors
            checkPfuncBounds(bounds, seq);
            // record the change
            if (!nodeHistory.containsKey(seq))
                nodeHistory.put(seq, new ArrayList<>());
            String confData = String.format("%s - %s %s: [%s, %s] --> [%s, %s]",
                    source,
                    seq,
                    this.confSearchNode.confToString(),
                    convertMagicBigDecimalToString(this.sequenceBounds.getOrDefault(seq, new MathTools.BigDecimalBounds()).lower),
                    convertMagicBigDecimalToString(this.sequenceBounds.getOrDefault(seq, new MathTools.BigDecimalBounds()).upper),
                    convertMagicBigDecimalToString(bounds.lower),
                    convertMagicBigDecimalToString(bounds.upper)
                    );
            nodeHistory.get(seq).add(confData);
        }
        this.sequenceBounds.put(seq, bounds);
        this.errorBounds.put(seq, bounds.size(PartitionFunction.decimalPrecision));
    }

    /**
     * Set the node's conformation energy bounds to a specified interval
     * @param bounds The bound interval
     * @param seq The sequence associated with the bounds
     * @param source A string describing the source of the bounds. Used for debug purposes
     */
    public synchronized void setConfBounds(MathTools.DoubleBounds bounds, Sequence seq, String source){
        if(debug) {
            // check the bounds for errors
            checkConfBounds(bounds, seq);
            // record the change
            if (!nodeHistory.containsKey(seq))
                nodeHistory.put(seq, new ArrayList<>());
            String confData = String.format("%s - %s %s: [%.6f, %.6f] --> [%.6f, %.6f]",
                    source,
                    seq,
                    this.confSearchNode.confToString(),
                    this.confBounds.getOrDefault(seq, new MathTools.DoubleBounds()).lower,
                    this.confBounds.getOrDefault(seq, new MathTools.DoubleBounds()).upper,
                    bounds.lower,
                    bounds.upper
            );
            nodeHistory.get(seq).add(confData);
        }
        this.confBounds.put(seq, bounds);
    }

    /**
     * Checks the bounds against the node's current conformation energy bounds, throwing errors if necessary
     * @param bounds Conformation energy bounds to check
     * @param seq The sequence associated with the bounds
     */
    private synchronized void checkConfBounds(MathTools.DoubleBounds bounds, Sequence seq){
        if(!bounds.isValid()) {
            System.err.println(String.format("Trying to update %s: %s with invalid bounds: [%.6f, %.6f]",
                    seq,
                    this.getConfSearchNode().confToString(),
                    bounds.lower,
                    bounds.upper
            ));
            throw new RuntimeException("Bounds are invalid. Exiting...");
        } else if(this.confBounds.containsKey(seq) && !this.confBounds.get(seq).contains(bounds)){
            System.err.println(String.format(
                    "Trying to update %s: %s with looser bounds: [%.6f, %.6f] --> [%.6f, %.6f]",
                    seq,
                    this.getConfSearchNode().confToString(),
                    bounds.lower,
                    bounds.upper,
                    this.confBounds.get(seq).lower,
                    this.confBounds.get(seq).upper
            ));
            throw new RuntimeException("Bounds are increasing in size. Exiting...");
        }

    }

    /**
     * Checks the bounds against the node's current partition-function bounds, throwing errors if necessary
     * @param bounds Partition-function bounds to check
     * @param seq The sequence associated with the bounds
     */
    private synchronized void checkPfuncBounds(MathTools.BigDecimalBounds bounds, Sequence seq){
        if(!bounds.isValid()) {
            System.err.println(String.format("Trying to update %s: %s with invalid bounds: [%1.6e, %1.6e]",
                    seq,
                    this.getConfSearchNode().confToString(),
                    bounds.lower,
                    bounds.upper
                    ));
            throw new RuntimeException("Bounds are invalid. Exiting...");
        } else if(this.sequenceBounds.containsKey(seq) && !this.sequenceBounds.get(seq).contains(bounds)){
            System.err.println(String.format(
                    "Trying to update %s: %s with looser bounds: [%1.6e, %1.6e] --> [%1.6e, %1.6e]",
                    seq,
                    this.getConfSearchNode().confToString(),
                    bounds.lower,
                    bounds.upper,
                    this.sequenceBounds.get(seq).lower,
                    this.sequenceBounds.get(seq).upper
            ));
            throw new RuntimeException("Bounds are increasing in size. Exiting...");
        }

    }

    public synchronized BigDecimal getUpperBound(Sequence seq){
        return getSequenceBounds(seq).upper;
    }

    public synchronized BigDecimal getLowerBound(Sequence seq){
        return getSequenceBounds(seq).lower;
    }

    public void index(ConfIndex confIndex) {
        confSearchNode.index(confIndex);
    }

    public Node getConfSearchNode() {
        return confSearchNode;
    }

    private MathTools.DoubleBounds getSequenceConfBounds(Sequence seq) {
        return confBounds.get(seq);
    }

    public synchronized double getConfLowerBound(Sequence seq) {
        return getSequenceConfBounds(seq).lower;
    }

    public synchronized double getConfUpperBound(Sequence seq) {
        return getSequenceConfBounds(seq).upper;
    }

    public boolean hasChildren(Sequence seq) {
        return childrenBySequence.containsKey(seq) &&
                !childrenBySequence.get(seq).isEmpty();
    }

    public List<MultiSequenceSHARKStarNode> getChildren(Sequence seq) {
        if(seq == null)
            return children;
        if(debug)
            checkChildren(seq);
        return childrenBySequence.get(seq);
    }

    /**
     * Returns an existing MultiSequenceSHARKStarNode child that corresponds to the Node, or null
     *
     * @param child The MultiSequenceSHARKStarNode.Node or "ConfSearchNode"
     * @return a MultiSequenceSHARKStarNode instance,  or null
     */
    public synchronized MultiSequenceSHARKStarNode getExistingChild(Node child){
        return childrenByRC.get(child.rc);
    }

    /**
     * Returns true if this node has a MultiSequenceSHARKStarNode child that corresponds to the given Node,
     * false otherwise.
     *
     * @param child The MultiSequenceSHARKStarNode.Node or "ConfSearchNode"
     * @return a MultiSequenceSHARKStarNode instance,  or null
     */
    public synchronized boolean hasExistingChild(Node child){
        return childrenByRC.containsKey(child.rc);
    }

    /**
     * Add a MultiSequenceSHARKStarNode as a child node of this node
     * @param child The MultiSequenceSHARKStarNode child
     * @param seq The sequence associated with the child
     */
    public synchronized void addChild(MultiSequenceSHARKStarNode child, Sequence seq){
        if(seq == null){
            children.add(child);
        }else {
            // Add the child to the childrenByRC Map
            childrenByRC.put(child.getConfSearchNode().rc, child);

            // Ensure that the childrenBySequence Map and List have been initialized properly
            if (!childrenBySequence.containsKey(seq) || childrenBySequence.get(seq) == null)
                childrenBySequence.put(seq, new ArrayList<>());
            // Add the child to the childrenBySequence Map
            childrenBySequence.get(seq).add(child);

            // Ensure that the childrenByAA Map and List have been initialized properly
            String AA = getAllowedAA(seq);
            if (!childrenByAA.containsKey(AA) || childrenByAA.get(AA) == null)
                childrenByAA.put(AA, new ArrayList<>());
            // Add the child to the childrenByAA Map
            childrenByAA.get(AA).add(child);
        }
        if(debug)
            checkChildren(seq);
    }

    private String getAllowedAA(Sequence seq) {
        String resNum = nextDesignPosition.resNum;
        String AA = nextDesignPosition.resTypes.get(0);
        if(seq.seqSpace.getResNums().contains(resNum))
            AA = seq.get(resNum).name;
        return AA;
    }

    private void checkAllChildren() {
        checkListForDupes(children, "(all sequences)");
    }

    private void checkListForDupes(List<MultiSequenceSHARKStarNode> list, String identifier) {
        Set<Integer> rcs = new HashSet<>();
        for(int i=0; i < list.size(); i++) {
            MultiSequenceSHARKStarNode node = list.get(i);
            int rc = node.confSearchNode.assignments[node.confSearchNode.pos];
            if(rcs.contains(rc)) {
                System.out.println("Dupe nodes for "+identifier+":");
                for(MultiSequenceSHARKStarNode nodecheck: list) {
                    if(nodecheck.confSearchNode.assignments[node.confSearchNode.pos] == rc)
                        System.out.println(nodecheck+"-"+nodecheck.confSearchNode.confToString());
                }
            }
            rcs.add(rc);
        }
    }

    private void checkChildren(Sequence seq) {
        if(!debug)
            return;
        checkAllChildren();
        if(hasChildren(seq)) {
            List<MultiSequenceSHARKStarNode> multiSequenceSHARKStarNodes = childrenBySequence.get(seq);
            checkListForDupes(multiSequenceSHARKStarNodes, seq.toString());
        }
        checkAllChildren();
    }

    public void checkDescendents(Sequence seq) {
        if(hasChildren(seq))
            System.out.println("Already expanded node?");
    }

    public synchronized void dumpHistory(Sequence sequence) {
        for(String history: nodeHistory.get(sequence))
            System.out.println(history);
    }

    public enum Type {
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

    public static MultiSequenceSHARKStarNode makeRoot(Node rootNode, SimpleConfSpace.Position nextDesignPosition) {
        return new MultiSequenceSHARKStarNode(rootNode, null, nextDesignPosition);
    }

    @Override
    public int compareTo(@NotNull MultiSequenceSHARKStarNode other){
        throw new UnsupportedOperationException("You can't compare multisequence nodes without a sequence.");
    }

    public synchronized BigDecimal getErrorBound(Sequence seq) {
        return errorBounds.get(seq);
    }

    public MathTools.BigDecimalBounds getSequenceBounds(Sequence seq) {
        if(isDebugConf(confSearchNode.assignments) && confBounds.containsKey(seq) && confBounds.get(seq).lower < 0
                && sequenceBounds.containsKey(seq)
                && MathTools.isLessThan(sequenceBounds.get(seq).upper, BigDecimal.ONE))
            System.err.println("Impossible bounds. Something is wrong.");
        if(!sequenceBounds.containsKey(seq)) {
            if(isDebugConf(confSearchNode.assignments))
                System.out.println("Gotcha-getsequence");
            sequenceBounds.put(seq, new MathTools.BigDecimalBounds(BigDecimal.ZERO, MathTools.BigPositiveInfinity));
        }
        return sequenceBounds.get(seq);
    }

    public String toSeqString(Sequence seq) {
        String out = confSearchNode.confToString();//fullConfSpace.formatConfRotamersWithResidueNumbers(confSearchNode.assignments);//
        BigDecimal subtreeLowerBound = getLowerBound(seq);
        BigDecimal subtreeUpperBound = getUpperBound(seq);
        out += "Energy:" + String.format("%4.2f", confSearchNode.getPartialConfLowerBound());
        if (!isMinimized(seq))
            out += " in [" + String.format("%4.4e,%4.4e", getSequenceConfBounds(seq).lower, getSequenceConfBounds(seq).upper)
                    + "]->[" + convertMagicBigDecimalToString(subtreeLowerBound) + "," + convertMagicBigDecimalToString(subtreeUpperBound) + "]";
        else
            out += " (minimized) -> " + convertMagicBigDecimalToString(subtreeLowerBound);
        return out;
    }

    public boolean isMinimized(Sequence seq) {
        double confLowerBound = getSequenceConfBounds(seq).lower;
        double confUpperBound = getSequenceConfBounds(seq).upper;
        return Math.abs(confLowerBound - confUpperBound) < 1e-5;
    }

    @Override
    public String toString(){
        return confSearchNode.confToString();
    }

    public static class Node implements PartialConfAStarNode {

        private static final int Unassigned = -1;
        private double partialConfLowerBound = Double.NaN;
        private double partialConfUpperBound = Double.NaN;
        public int[] assignments;
        public int pos = Unassigned;
        public int rc = Unassigned;
        public final int level;

        public Node(int size) {
            this(size, 0);
        }

        public Node(int size, int level) {
            assignments = new int[size];
            Arrays.fill(assignments, Unassigned);
            this.level = level;
            if(this.level == 0) {
                partialConfUpperBound = 0;
                partialConfLowerBound = 0;
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
        public int getLevel() {
            return level;
        }

        @Override
        public void index(@NotNull ConfIndex confIndex) {
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
            return Arrays.toString(this.assignments);
        }

        public double getPartialConfLowerBound() {
            return partialConfLowerBound;
        }

        public void setPartialConfLowerAndUpper(double partialConfLowerBound, double partialConfUpperBound) {
            this.partialConfLowerBound = partialConfLowerBound;
            this.partialConfUpperBound = partialConfUpperBound;
        }

        public double getPartialConfUpperBound() {
            return partialConfUpperBound;
        }

    }
}
