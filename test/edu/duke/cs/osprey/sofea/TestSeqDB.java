package edu.duke.cs.osprey.sofea;

import static edu.duke.cs.osprey.tools.MathTools.biggen;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import com.google.common.collect.Iterators;
import edu.duke.cs.osprey.TestBase.TempFile;
import edu.duke.cs.osprey.confspace.*;
import edu.duke.cs.osprey.structure.Molecule;
import edu.duke.cs.osprey.structure.PDBIO;
import edu.duke.cs.osprey.tools.MathTools.BigDecimalBounds;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Arrays;


public class TestSeqDB {

	private static MathContext mathContext = new MathContext(16, RoundingMode.HALF_UP);
	private static BigDecimalBounds emptySum = new BigDecimalBounds(BigDecimal.ZERO, BigDecimal.ZERO);

	@Test
	public void empty() {

		MultiStateConfSpace confSpace = makeConfSpace();

		try (TempFile file = new TempFile("seq.db")) {
			try (SeqDB seqdb = new SeqDB(confSpace, mathContext, file)) {

				// there shouldn't be any info
				for (MultiStateConfSpace.State state : confSpace.unsequencedStates) {
					assertThat(seqdb.getUnsequencedSum(state), is(emptySum));
				}
				assertThat(Iterators.size(seqdb.getSequencedSums().iterator()), is(0));
			}
		}
	}

	@Test
	public void commitEmptyTransaction() {

		MultiStateConfSpace confSpace = makeConfSpace();

		try (TempFile file = new TempFile("seq.db")) {
			try (SeqDB seqdb = new SeqDB(confSpace, mathContext, file)) {

				SeqDB.Transaction tx = seqdb.transaction();
				assertThat(tx.isEmpty(), is(true));
				tx.commit();

				// there shouldn't be any info
				for (MultiStateConfSpace.State state : confSpace.unsequencedStates) {
					assertThat(seqdb.getUnsequencedSum(state), is(emptySum));
				}
				assertThat(Iterators.size(seqdb.getSequencedSums().iterator()), is(0));
			}
		}
	}

	@Test
	public void ignoreTransaction() {

		MultiStateConfSpace confSpace = makeConfSpace();
		MultiStateConfSpace.State design = confSpace.getState("design");

		try (TempFile file = new TempFile("seq.db")) {
			try (SeqDB seqdb = new SeqDB(confSpace, mathContext, file)) {

				SeqDB.Transaction tx = seqdb.transaction();
				assertThat(tx.isEmpty(), is(true));

				tx.addZPath(design, design.confSpace.makeUnassignedSequence(), BigDecimal.ONE, BigDecimal.ONE);

				assertThat(tx.isEmpty(), is(false));

				// there shouldn't be any info
				for (MultiStateConfSpace.State state : confSpace.unsequencedStates) {
					assertThat(seqdb.getUnsequencedSum(state), is(emptySum));
				}
				assertThat(Iterators.size(seqdb.getSequencedSums().iterator()), is(0));
			}
		}
	}

	@Test
	public void add() {

		MultiStateConfSpace confSpace = makeConfSpace();
		MultiStateConfSpace.State target = confSpace.getState("target");
		MultiStateConfSpace.State design = confSpace.getState("design");
		MultiStateConfSpace.State complex = confSpace.getState("complex");

		try (TempFile file = new TempFile("seq.db")) {
			try (SeqDB seqdb = new SeqDB(confSpace, mathContext, file)) {

				Sequence seq = confSpace.seqSpace.makeUnassignedSequence();

				SeqDB.Transaction tx = seqdb.transaction();
				tx.addZSumUpper(target, seq, biggen(2.0));
				tx.addZSumUpper(design, seq, biggen(4.0));
				tx.addZSumUpper(complex, seq, biggen(6.0));
				tx.commit();

				assertThat(seqdb.getUnsequencedZSumBounds(target), is(new BigDecimalBounds(0.0, 2.0)));
				assertThat(seqdb.getSequencedSums(seq).get(design), is(new BigDecimalBounds(0.0, 4.0)));
				assertThat(seqdb.getSequencedSums(seq).get(complex), is(new BigDecimalBounds(0.0, 6.0)));
			}
		}
	}

	@Test
	public void addAdd() {

		MultiStateConfSpace confSpace = makeConfSpace();
		MultiStateConfSpace.State target = confSpace.getState("target");
		MultiStateConfSpace.State design = confSpace.getState("design");
		MultiStateConfSpace.State complex = confSpace.getState("complex");

		try (TempFile file = new TempFile("seq.db")) {
			try (SeqDB seqdb = new SeqDB(confSpace, mathContext, file)) {

				Sequence seq = confSpace.seqSpace.makeUnassignedSequence();

				SeqDB.Transaction tx = seqdb.transaction();
				tx.addZSumUpper(target, seq, biggen(2.0));
				tx.addZSumUpper(design, seq, biggen(4.0));
				tx.addZSumUpper(complex, seq, biggen(6.0));
				tx.commit();

				tx = seqdb.transaction();
				tx.addZSumUpper(target, seq, biggen(2.0));
				tx.addZSumUpper(design, seq, biggen(4.0));
				tx.addZSumUpper(complex, seq, biggen(6.0));
				tx.commit();

				assertThat(seqdb.getUnsequencedZSumBounds(target), is(new BigDecimalBounds(0.0, 4.0)));
				assertThat(seqdb.getSequencedSums(seq).get(design), is(new BigDecimalBounds(0.0, 8.0)));
				assertThat(seqdb.getSequencedSums(seq).get(complex), is(new BigDecimalBounds(0.0, 12.0)));
			}
		}
	}

	@Test
	public void addSub() {

		MultiStateConfSpace confSpace = makeConfSpace();
		MultiStateConfSpace.State target = confSpace.getState("target");
		MultiStateConfSpace.State design = confSpace.getState("design");
		MultiStateConfSpace.State complex = confSpace.getState("complex");

		try (TempFile file = new TempFile("seq.db")) {
			try (SeqDB seqdb = new SeqDB(confSpace, mathContext, file)) {

				Sequence seq = confSpace.seqSpace.makeUnassignedSequence();

				SeqDB.Transaction tx = seqdb.transaction();
				tx.addZSumUpper(target, seq, biggen(2.0));
				tx.addZSumUpper(design, seq, biggen(4.0));
				tx.addZSumUpper(complex, seq, biggen(6.0));
				tx.commit();

				tx = seqdb.transaction();
				tx.subZSumUpper(target, seq, biggen(2.0));
				tx.subZSumUpper(design, seq, biggen(4.0));
				tx.subZSumUpper(complex, seq, biggen(6.0));
				tx.commit();

				assertThat(seqdb.getUnsequencedZSumBounds(target), is(emptySum));
				assertThat(seqdb.getSequencedSums(seq).get(design), is(emptySum));
				assertThat(seqdb.getSequencedSums(seq).get(complex), is(emptySum));
			}
		}
	}

	@Test
	public void addSubAdd() {

		MultiStateConfSpace confSpace = makeConfSpace();
		MultiStateConfSpace.State target = confSpace.getState("target");
		MultiStateConfSpace.State design = confSpace.getState("design");
		MultiStateConfSpace.State complex = confSpace.getState("complex");

		try (TempFile file = new TempFile("seq.db")) {
			try (SeqDB seqdb = new SeqDB(confSpace, mathContext, file)) {

				Sequence seq = confSpace.seqSpace.makeUnassignedSequence();

				SeqDB.Transaction tx = seqdb.transaction();
				tx.addZSumUpper(target, seq, biggen(2.0));
				tx.addZSumUpper(design, seq, biggen(4.0));
				tx.addZSumUpper(complex, seq, biggen(6.0));
				tx.commit();

				tx = seqdb.transaction();
				tx.addZPath(target, seq, biggen(1.0), biggen(2.0));
				tx.addZPath(design, seq, biggen(3.0), biggen(4.0));
				tx.addZPath(complex, seq, biggen(4.0), biggen(6.0));
				tx.commit();

				assertThat(seqdb.getUnsequencedZSumBounds(target), is(new BigDecimalBounds(1.0, 1.0)));
				assertThat(seqdb.getSequencedSums(seq).get(design), is(new BigDecimalBounds(3.0, 3.0)));
				assertThat(seqdb.getSequencedSums(seq).get(complex), is(new BigDecimalBounds(4.0, 4.0)));
			}
		}
	}

	private static MultiStateConfSpace makeConfSpace() {

		Molecule pdb = PDBIO.readResource("/1CC8.ss.pdb");

		Strand design = new Strand.Builder(pdb)
			.setResidues("A68", "A73")
			.build();
		for (String resNum : Arrays.asList("A68", "A69")) {
			design.flexibility.get(resNum)
				.setLibraryRotamers(Strand.WildType, "GLY")
				.addWildTypeRotamers()
				.setContinuous();
		}

		Strand target = new Strand.Builder(pdb)
			.setResidues("A2", "A67")
			.build();
		for (String resNum : Arrays.asList("A5", "A6")) {
			target.flexibility.get(resNum)
				.setLibraryRotamers(Strand.WildType)
				.addWildTypeRotamers()
				.setContinuous();
		}

		// make a multi-state conf space
		return new MultiStateConfSpace
			.Builder("design", new SimpleConfSpace.Builder().addStrands(design).build())
			.addMutableState("complex", new SimpleConfSpace.Builder().addStrands(design, target).build())
			.addUnmutableState("target", new SimpleConfSpace.Builder().addStrands(target).build())
			.build();
	}
}
