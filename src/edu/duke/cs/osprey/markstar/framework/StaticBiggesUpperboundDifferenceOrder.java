/*
** This file is part of OSPREY 3.0
** 
** OSPREY Protein Redesign Software Version 3.0
** Copyright (C) 2001-2018 Bruce Donald Lab, Duke University
** 
** OSPREY is free software: you can redistribute it and/or modify
** it under the terms of the GNU General Public License version 2
** as published by the Free Software Foundation.
** 
** You should have received a copy of the GNU General Public License
** along with OSPREY.  If not, see <http://www.gnu.org/licenses/>.
** 
** OSPREY relies on grants for its development, and since visibility
** in the scientific literature is essential for our success, we
** ask that users of OSPREY cite our papers. See the CITING_OSPREY
** document in this distribution for more information.
** 
** Contact Info:
**    Bruce Donald
**    Duke University
**    Department of Computer Science
**    Levine Science Research Center (LSRC)
**    Durham
**    NC 27708-0129
**    USA
**    e-mail: www.cs.duke.edu/brd/
** 
** <signature of Bruce Donald>, Mar 1, 2018
** Bruce Donald, Professor of Computer Science
*/

package edu.duke.cs.osprey.markstar.framework;

import edu.duke.cs.osprey.astar.conf.ConfIndex;
import edu.duke.cs.osprey.astar.conf.PartialConfAStarNode;
import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.astar.conf.order.AStarOrder;
import edu.duke.cs.osprey.astar.conf.scoring.AStarScorer;
import edu.duke.cs.osprey.kstar.pfunc.BoltzmannCalculator;
import edu.duke.cs.osprey.kstar.pfunc.PartitionFunction;
import edu.duke.cs.osprey.tools.MathTools;

import java.math.BigDecimal;
import java.util.*;

public class StaticBiggesUpperboundDifferenceOrder<T extends PartialConfAStarNode> implements AStarOrder<T> {

	public final MathTools.Optimizer optimizer;
	private List<Integer> posOrder;
	private final BoltzmannCalculator calculator = new BoltzmannCalculator(PartitionFunction.decimalPrecision);

	public StaticBiggesUpperboundDifferenceOrder() {
		this(MathTools.Optimizer.Maximize);
	}

	public StaticBiggesUpperboundDifferenceOrder(MathTools.Optimizer optimizer) {
		this.optimizer = optimizer;
	}

	private AStarScorer<T> gscorer;
	private AStarScorer<T> hscorer;

	@Override
	public void setScorers(AStarScorer<T> gscorer, AStarScorer<T> hscorer) {
		this.gscorer = gscorer;
		this.hscorer = hscorer;
	}

	@Override
	public boolean isDynamic() {
		return false;
	}

	@Override
	public int getNextPos(ConfIndex<T> confIndex, RCs rcs) {
		if (posOrder == null) {
			posOrder = calcPosOrder(confIndex, rcs);
		}
		return posOrder.get(confIndex.node.getLevel());
	}

	private List<Integer> calcPosOrder(ConfIndex<T> confIndex, RCs rcs) {
		// init permutation array with only undefined positions and score them
		List<Integer> undefinedOrder = new ArrayList<>();
		Map<Integer,BigDecimal> scores = new TreeMap<>();
		for (int posi=0; posi<confIndex.numUndefined; posi++) {
			int pos = confIndex.undefinedPos[posi];
			undefinedOrder.add(pos);
			scores.put(pos, scorePos(confIndex, rcs, pos));
		}

		// sort positions in order of decreasing score
		Collections.sort(undefinedOrder, new Comparator<Integer>() {

			@Override
			public int compare(Integer pos1, Integer pos2) {
				BigDecimal score1 = scores.get(pos1);
				BigDecimal score2 = scores.get(pos2);
				// NOTE: use reverse order for decreasing sort
				return score2.compareTo(score1);
			}
		});

		// prepend the defined positions to build the full order
		List<Integer> order = new ArrayList<>();
		for (int posi=0; posi<confIndex.numDefined; posi++) {
			int pos = confIndex.definedPos[posi];
			order.add(pos);
		}
		order.addAll(undefinedOrder);


		return order;
	}

	private void putResidueInPosition(List<Integer> order, int residueIndex, int orderIndex) {
	    swapPos(order, residueIndex, order.get(orderIndex));
	}

	private void swapPos(List<Integer> order, int a, int b) {
		int aIndex = order.indexOf(a);
		int bIndex = order.indexOf(b);

		order.set(bIndex, a);
		order.set(aIndex, b);
	}


	BigDecimal scorePos(ConfIndex<T> confIndex, RCs rcs, int pos) {

		// check all the RCs at this pos and aggregate the energies
		BigDecimal maxUpper = MathTools.BigNegativeInfinity;
		BigDecimal minUpper = MathTools.BigPositiveInfinity;
		for (int rc : rcs.get(pos)) {
			double childScore = gscorer.calcDifferential(confIndex, rcs, pos, rc)
				+ hscorer.calcDifferential(confIndex, rcs, pos, rc);
			BigDecimal childWeightedScore =  calculator.calc(childScore);
			if(MathTools.isLessThan(childWeightedScore, minUpper))
				minUpper = childWeightedScore;
			if(MathTools.isGreaterThan(childWeightedScore, maxUpper))
				maxUpper = childWeightedScore;
		}

		return maxUpper.subtract(minUpper);
	}
}
