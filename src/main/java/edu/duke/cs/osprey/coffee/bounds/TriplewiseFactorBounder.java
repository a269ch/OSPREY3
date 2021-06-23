package edu.duke.cs.osprey.coffee.bounds;

import edu.duke.cs.osprey.astar.conf.ConfIndex;
import edu.duke.cs.osprey.coffee.nodedb.NodeTree;
import edu.duke.cs.osprey.coffee.zmat.ClusterZMatrix;
import edu.duke.cs.osprey.coffee.zmat.Triple;
import edu.duke.cs.osprey.tools.BigExp;

public class TriplewiseFactorBounder implements Bounder{
    private final TriplewiseBounder triplewiseBounder;
    private final PairwiseFactorBounder factorBounder;
    private final ClusterZMatrix zmat;

    public TriplewiseFactorBounder(ClusterZMatrix zmat) {
        this.zmat = zmat;
        this.triplewiseBounder = new TriplewiseBounder(zmat);
        this.factorBounder = new PairwiseFactorBounder(zmat);
    }

    @Override
    public BigExp g(ConfIndex index) {
        return triplewiseBounder.g(index);
    }

    @Override
    public BigExp h(ConfIndex index, NodeTree tree) {
        //TODO: can we take advantage of triples in the factor bound form?
        return factorBounder.h(index, tree);
    }
}
