/*
 *  This file is part of the SIRIUS library for analyzing MS and MS/MS data
 *
 *  Copyright (C) 2013-2015 Kai Dührkop
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with SIRIUS.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.unijena.bioinf.ChemistryBase.chem;

/**
 * Ionization mode in which a small ion (adduct) is attached to the molecule ([M+ion]), an ion is removed from the
 * molecule ([M-ion]) or the molecule itself is an ion ([M]).
 *
 */
public class IonMode extends Ionization {
	private final double mass;
	private final int charge;
	private final String name;
	private final MolecularFormula molecularFormula;

    /**
     * Construct an adduct from a charge and a molecular name. The mass is computed as mass(adduct) - charge * electron mass
     * @param charge  charge of the ionization
     * @param name the name has usually the format [M+'name']'charge'
     * @param formula the molecular name of the adduct. May be negative, if it is subtracted from the neutral molecule
     */
	public IonMode(int charge, String name, MolecularFormula formula) {
		this(formula.getMass() - charge*Charge.ELECTRON_MASS, charge, name, formula);
	}
	
	public IonMode(double mass, int charge, String name, MolecularFormula formula) {
		this.mass = mass;
		this.charge = charge;
		if (formula == null) throw new NullPointerException("Expect non-null name");
		this.name = name;
		this.molecularFormula = formula;
	}
	
	public IonMode(double mass, int charge, String formula) {
		this.mass = mass;
		this.charge = charge;
		if (formula == null) throw new NullPointerException("Expect non-null name");
		this.name = formula;
		this.molecularFormula = MolecularFormula.emptyFormula();
	}
	
	/**
     * if the adduct is known, return its molecular name (may be negative). Protons and electrons should not be added (because they
     * are no atoms)! In general: add only atoms to the name which have isotopic species, because this method is
     * used for isotopic pattern generation of a name with the given ionization mode.
     */
    public MolecularFormula getAtoms() {
    	return molecularFormula;
    }

	@Override
	public double getMass() {
		return mass;
	}

	@Override
	public int getCharge() {
		return charge;
	}

	@Override
	public String getName() {
		return name;
	}

    @Override
    public String toString() {
        return name;
    }
}
