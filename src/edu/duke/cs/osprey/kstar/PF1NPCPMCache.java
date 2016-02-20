package edu.duke.cs.osprey.kstar;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import edu.duke.cs.osprey.confspace.SearchProblem;
import edu.duke.cs.osprey.control.ConfigFileParser;
import edu.duke.cs.osprey.dof.deeper.DEEPerSettings;
import edu.duke.cs.osprey.pruning.PruningControl;
import edu.duke.cs.osprey.tools.ObjectIO;

/**
 * 
 * @author Adegoke Ojewole (ao68@duke.edu)
 *
 */
public class PF1NPCPMCache extends PF1NPMCache {

	ArrayList<Integer> indexes = new ArrayList<>();
	ArrayList<SearchProblem> sps = new ArrayList<>();
	ArrayList<KSConf> partialQConfs = new ArrayList<>();

	protected PF1NPCPMCache(ArrayList<String> sequence, ConfigFileParser cfp, 
			SearchProblem sp, PruningControl pc, DEEPerSettings dset, 
			ArrayList<String[]> moveableStrands, ArrayList<String[]> freeBBZones, 
			double EW_I0) {
		
		super( sequence, cfp, sp, pc, dset, moveableStrands, freeBBZones, EW_I0);

		// initialize parallel data structures
		for( int it = 0; it < PFAbstract.getNumThreads(); ++it ) indexes.add(indexes.size());
		indexes.trimToSize();

		// DeepCopy searchproblem
		try {

			for( int i = 0; i < PFAbstract.getNumThreads(); ++i )
				sps.add((SearchProblem)ObjectIO.deepCopy(sp));

		} catch( Exception e ) {
			System.out.println(e.getMessage());
			e.printStackTrace();
			System.exit(1);
		}
		sps.trimToSize();
	}


	protected void start() {
		try {
			
			setRunState(RunState.STARTED);

			partialQConfs.clear();
			for( int it = 0; it < PFAbstract.getNumThreads(); ++it ) partialQConfs.add(null);
			partialQConfs.trimToSize();
			
			confs = new KSConfQ( this, sp, PFAbstract.getNumThreads() );

			// set pstar
			if( confs.getNextConf() ) {

				KSConf conf = confs.peek();

				setPStar( conf.getMinEnergyLowerBound() );
			}

			startTime = System.currentTimeMillis();

			confs.start();
			
			if( waitUntilCapacity )
				confs.waitUntilCapacity();

		} catch(Exception e) {
			System.out.println(e.getMessage());
			e.printStackTrace();
			System.exit(1);
		}

	}


	protected void iterate() throws Exception {

		synchronized( confs.qLock ) {

			if( confs.size() < PFAbstract.getNumThreads() ) confs.qLock.wait();

			for( int i = 0; i < PFAbstract.getNumThreads(); ++i ) {
				
				if( confs.size() > 0 )
					partialQConfs.set(i, confs.deQueue());
				
				else {
					partialQConfs.remove(partialQConfs.size()-1);
					indexes.remove(indexes.size()-1);
				}
			}
			
			minimizingConfs = minimizingConfs.add( BigInteger.valueOf(partialQConfs.size()) );

			if( confs.getState() == Thread.State.WAITING ) confs.qLock.notify();
		}

		// minimization hapens here
		if( (eAppx = accumulate(partialQConfs)) != EApproxReached.FALSE ) {
			// we leave this function

			confs.cleanUp();
		}

	}


	protected void computeSlice() {

		try {
			/*
			synchronized( confs.qLock ) {
				if( confs.getState() == Thread.State.WAITING ) 
					confs.qLock.notify();
			}
			*/
			iterate();

		} catch(Exception e) {
			e.printStackTrace();
			System.exit(1);
		}

	}


	protected void compute() {

		try {

			// process conformations until e-approximation reached
			while( eAppx == EApproxReached.FALSE ) {

				iterate();
			}

		} catch(Exception e) {
			System.out.println(e.getMessage());
			e.printStackTrace();
			System.exit(1);
		}
	}


	protected BigDecimal computePartialQDagger( ArrayList<KSConf> partialQConfs ) {
		BigDecimal partialQDagger = BigDecimal.ZERO;

		for( KSConf conf : partialQConfs )
			partialQDagger = partialQDagger.add( getBoltzmannWeight(conf.getMinEnergyLowerBound()) );

		return partialQDagger;
	}


	protected EApproxReached accumulate( ArrayList<KSConf> partialQConfs ) {

		if( partialQConfs.size() == 0 )
			return eAppx;
		
		// we do not have a lock when minimizing
		indexes.parallelStream().forEach( i -> {
			partialQConfs.get(i).setMinEnergy( sps.get(i).minimizedEnergy(partialQConfs.get(i).getConf()) );
		});

		double E = 0;
		
		// we need a current snapshot of qDagger, so we lock here
		synchronized( confs.qLock ) {
			// update q*, qDagger, minimizingConfs, and q' atomically
			
			confs.setQDagger( confs.getQDagger().subtract( computePartialQDagger(partialQConfs) ) );

			Et = confs.peekTail() != null ? confs.peekTail().getMinEnergyLowerBound() 
					: partialQConfs.get(partialQConfs.size()-1).getMinEnergyLowerBound();

			for( KSConf conf : partialQConfs ) {
				
				minimizingConfs = minimizingConfs.subtract( BigInteger.ONE );
				
				E = conf.getMinEnergy();
				updateQStar( conf );
				
				// negative values of effective epsilon are disallowed
				if( (effectiveEpsilon = computeEffectiveEpsilon()) < 0 ) return EApproxReached.NOT_POSSIBLE;
			
				if( effectiveEpsilon <= targetEpsilon ) break;
			}

			long currentTime = System.currentTimeMillis();
			
			System.out.println(Et + "\t" + E + "\t" + effectiveEpsilon + "\t" + 
					getNumMinimizedConfs() + "\t" + getNumUnMinimizedConfs() + "\t" + ((currentTime-startTime)/1000));
		}

		return effectiveEpsilon > targetEpsilon ? EApproxReached.FALSE : EApproxReached.TRUE;
	}

}
