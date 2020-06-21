package edu.duke.cs.osprey.coffee;

import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.coffee.directions.Directions;
import edu.duke.cs.osprey.coffee.nodedb.NodeDB;
import edu.duke.cs.osprey.coffee.nodedb.NodeIndex;
import edu.duke.cs.osprey.coffee.seqdb.SeqDB;
import edu.duke.cs.osprey.confspace.Conf;
import edu.duke.cs.osprey.confspace.Sequence;
import edu.duke.cs.osprey.parallelism.TaskExecutor;
import edu.duke.cs.osprey.parallelism.ThreadTools;
import edu.duke.cs.osprey.tools.BigExp;
import edu.duke.cs.osprey.tools.Stopwatch;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class NodeProcessor {

	private static class MinimizationQueue {

		final int size;
		final Deque<NodeIndex.Node> queue;

		MinimizationQueue(StateInfo stateInfo) {
			size = stateInfo.config.ecalc.maxBatchSize();
			queue = new ArrayDeque<>(size);
		}

		/**
		 * Adds the node to the queue.
		 * If the queue is full, drains the queue and returns the nodes.
		 */
		public synchronized List<NodeIndex.Node> add(NodeIndex.Node node) {

			assert (queue.size() < size);
			queue.add(node);

			if (queue.size() == size) {
				var nodes = new ArrayList<>(queue);
				queue.clear();
				return nodes;
			}

			return null;
		}

		/**
		 * Removes all nodes from the queue.
		 */
		public synchronized List<NodeIndex.Node> flush() {

			if (!queue.isEmpty()) {
				var nodes = new ArrayList<>(queue);
				queue.clear();
				return nodes;
			}

			return null;
		}
	}

	public final TaskExecutor tasks;
	public final SeqDB seqdb;
	public final NodeDB nodedb;
	public final StateInfo[] stateInfos;

	private final List<int[]> posPermutations;
	private final List<MinimizationQueue> minimizationQueues;

	public NodeProcessor(TaskExecutor tasks, SeqDB seqdb, NodeDB nodedb, StateInfo[] stateInfos) {

		this.tasks = tasks;
		this.seqdb = seqdb;
		this.nodedb = nodedb;
		this.stateInfos = stateInfos;

		// pick an ordering of the positions that speeds things up
		// for now, sequence positions at the top is a good heuristic
		// TODO: within seq/conf strata, high branch factor at the top?
		posPermutations = Arrays.stream(stateInfos)
			.map(stateInfo -> Arrays.stream(stateInfo.confSpace.positions)
				.sorted(Comparator.comparing(pos -> !pos.hasMutations))
				.mapToInt(pos -> pos.index)
				.toArray()
			)
			.collect(Collectors.toList());

		// set up the minimization queues
		minimizationQueues = Arrays.stream(stateInfos)
			.map(stateInfo -> new MinimizationQueue(stateInfo))
			.collect(Collectors.toList());
	}

	private void log(String msg, Object ... args) {
		nodedb.member.log(msg, args);
	}

	public boolean processFor(Directions directions, long duration, TimeUnit timeUnit) {

		boolean foundNodes = false;

		// process nodes for the specified duration
		long stopNs = System.nanoTime() + timeUnit.toNanos(duration);
		while (System.nanoTime() < stopNs) {

			var nodeInfo = getNode(directions);
			switch (nodeInfo.result) {

				// process the node in a task
				case GotNode -> {
					foundNodes = true;
					tasks.submit(
						() -> process(nodeInfo),
						ignored -> {}
					);
				}

				// no nodes, wait a bit and try again
				default -> ThreadTools.sleep(100, TimeUnit.MILLISECONDS);
			}
		}

		// flush any minimization batches
		for (var stateInfo : stateInfos) {
			var statei = stateInfo.config.state.index;
			var nodes = minimizationQueues.get(statei).flush();
			if (nodes != null) {
				tasks.submit(
					() -> minimize(statei, nodes),
					ignored -> {}
				);
			}
		}

		tasks.waitForFinish();

		return foundNodes;
	}

	public enum Result {

		GotNode(true),
		NoInfo(false),
		NoNode(false);

		public final boolean gotNode;

		Result(boolean gotNode) {
			this.gotNode = gotNode;
		}
	}

	public static class NodeInfo {

		public final Result result;
		public final NodeIndex.Node node;
		public final RCs tree;

		public NodeInfo(Result result, NodeIndex.Node node, RCs tree) {
			this.result = result;
			this.node = node;
			this.tree = tree;
		}

		public NodeInfo(Result result) {
			this(result, null, null);
		}
	}

	public Sequence makeSeq(int statei, int[] conf) {
		if (stateInfos[statei].config.state.isSequenced) {
			return seqdb.confSpace.seqSpace.makeSequence(stateInfos[statei].confSpace, conf);
		} else {
			return null;
		}
	}

	public NodeInfo getNode(Directions directions) {

		// get the currently focused state
		int statei = directions.getFocusedStatei();
		if (statei < 0) {
			return new NodeInfo(Result.NoInfo);
		}

		// get the tree for this state
		RCs tree = directions.getTree(statei);
		if (tree == null) {
			return new NodeInfo(Result.NoInfo);
		}

		// get the next node from that state
		var node = nodedb.removeHigh(statei);
		if (node == null) {
			return new NodeInfo(Result.NoNode);
		}

		// all is well
		return new NodeInfo(Result.GotNode, node, tree);
	}

	public NodeInfo process(NodeInfo nodeInfo) {

		if (nodeInfo.node.isLeaf()) {

			// see if this node's score is roughly as good as current predictions
			var currentScore = nodedb.perf.score(nodeInfo.node);
			if (nodeInfo.node.score.exp - currentScore.exp > 1) {

				// nope, it should have a much worse score
				// re-score it and put it back into nodedb
				nodedb.add(new NodeIndex.Node(nodeInfo.node, currentScore));

			} else {

				// the score looks good, minimize it
				minimize(nodeInfo.node);
			}
		} else {

			// interior node, expand it
			expand(nodeInfo);
		}

		return nodeInfo;
	}

	private void minimize(NodeIndex.Node node) {

		// the score look good, add it to the minimization queue
		var q = minimizationQueues.get(node.statei);
		List<NodeIndex.Node> nodes = q.add(node);

		// if we finished a batch, minimize it now
		if (nodes != null) {
			minimize(node.statei, nodes);
		}
	}

	private List<NodeIndex.Node> minimize(int statei, List<NodeIndex.Node> nodes) {

		// got a full batch, minimize it!
		var stateInfo = stateInfos[statei];

		// collect timing info for the minimizations
		Stopwatch stopwatch = new Stopwatch().start();

		// actually do the minimizations
		var zs = stateInfo.zPaths(nodes.stream()
			.map(node -> node.conf)
			.collect(Collectors.toList()));

		// update seqdb
		var seqdbBatch = seqdb.batch();
		for (int i=0; i<nodes.size(); i++) {
			var n = nodes.get(i);
			seqdbBatch.addZConf(
				stateInfo.config.state,
				makeSeq(n.statei, n.conf),
				zs.get(i),
				n.zSumUpper
			);
		}
		seqdbBatch.save();

		// update node performance
		stopwatch.stop();
		for (int i=0; i<nodes.size(); i++) {
			var n = nodes.get(i);
			var reduction = n.zSumUpper;
			long ns = stopwatch.getTimeNs()/nodes.size();
			nodedb.perf.update(n, ns, reduction);

			/* TEMP
			log("            minimization:   r %s  %10s  r/t %s",
				reduction,
				stopwatch.getTime(2),
				Log.formatBigEngineering(processor.seqdb.bigMath()
					.set(reduction)
					.div(stopwatch.getTimeNs())
					.get()
				)
			);
			*/
		}

		return nodes;
	}

	private void expand(NodeInfo nodeInfo) {

		// get timing info for the node expansion
		Stopwatch stopwatch = new Stopwatch().start();

		int statei = nodeInfo.node.statei;
		var stateInfo = stateInfos[statei];
		var confIndex = stateInfo.makeConfIndex();
		Conf.index(nodeInfo.node.conf, confIndex);

		// remove the old bound from SeqDB
		var seqdbBatch = seqdb.batch();
		seqdbBatch.subZSumUpper(
			stateInfo.config.state,
			makeSeq(statei, nodeInfo.node.conf),
			nodeInfo.node.zSumUpper
		);

		// track the reduction in uncertainty for this node
		var reduction = new BigExp(nodeInfo.node.zSumUpper);

		// pick the next position to expand, according to the position permutation
		int posi = posPermutations.get(statei)[confIndex.numDefined];

		// expand the node at the picked position
		for (int confi : nodeInfo.tree.get(posi)) {
			confIndex.assignInPlace(posi, confi);

			// compute an upper bound for the assignment
			var zSumUpper = stateInfo.zSumUpper(confIndex, nodeInfo.tree).normalize(true);

			// update nodedb
			var conf = Conf.make(confIndex);
			var childNode = new NodeIndex.Node(
				statei, conf, zSumUpper,
				nodedb.perf.score(statei, conf, zSumUpper)
			);
			nodedb.add(childNode);

			// add the new bound
			seqdbBatch.addZSumUpper(
				stateInfo.config.state,
				makeSeq(statei, conf),
				zSumUpper
			);

			// update the reduction calculation
			reduction.sub(zSumUpper);

			confIndex.unassignInPlace(posi);
		}

		seqdbBatch.save();

		// compute the uncertainty reduction and update the node performance
		stopwatch.stop();
		nodedb.perf.update(nodeInfo.node, stopwatch.getTimeNs(), reduction);

		/* TEMP
		log("node expansion:   r %s  %10s  r/s %s",
			reduction,
			stopwatch.getTime(2),
			Log.formatBigEngineering(seqdb.bigMath()
				.set(reduction)
				.div(stopwatch.getTimeNs())
				.get()
			)
		);
		*/
	}

	public void handleDrops(Stream<NodeIndex.Node> nodes) {

		// NOTE: this is called on the NodeDB thread!

		var batch = seqdb.batch();
		nodes.forEach(node ->
			batch.drop(
				stateInfos[node.statei].config.state,
				makeSeq(node.statei, node.conf),
				node.zSumUpper
			)
		);
		batch.save();
	}
}
