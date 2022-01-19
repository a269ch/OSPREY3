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


package edu.duke.cs.osprey.structure;


import org.junit.Test;

import edu.duke.cs.osprey.structure.PDBIO;




public class TestFragment {

    @Test
    public void fragmentMolecule(){
        Molecule mol = PDBIO.readFile("examples/MFCC/TPEP.pdb");
        mol.fragment();
        for(int i=0; i<mol.fragments.size(); i++){
            PDBIO.writeFile(mol.fragments.get(i), "examples/MFCC/fragments/fragment_"+i+".pdb");
        }
        for(int i=0; i<mol.concaps.size(); i++){
            PDBIO.writeFile(mol.concaps.get(i), "examples/MFCC/fragments/cap_"+i+".pdb");
        }
    }
}
