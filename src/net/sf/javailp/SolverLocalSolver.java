/**
 * Java ILP is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Java ILP is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Java ILP. If not, see http://www.gnu.org/licenses/.
 */
package net.sf.javailp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;

import ilog.concert.IloNumVar;

import java.util.Set;

import localsolver.LSExpression;
import localsolver.LSModel;
import localsolver.LSOperator;
import localsolver.LSSolution;
import localsolver.LocalSolver;

/**
 * The {@code SolverLocalSolver} is the {@code Solver} LocalSolver.
 * 
 * @author chicano
 * 
 */
public class SolverLocalSolver extends AbstractSolver {

	/**
	 * The {@code Hook} for the {@code SolverLocalSolver}.
	 * 
	 * @author chicano
	 * 
	 */
	public interface Hook {

		/**
		 * This method is called once before the optimization and allows to
		 * change some internal settings.
		 * 
		 * @param model
		 *            the LocalSolver solver
		 * @param varToNum
		 *            the map of variables to LocalSolver specific variables
		 */
		public void call(LSModel model, Map<Object, LSExpression> varToNum);
	}

	protected final Set<Hook> hooks = new HashSet<Hook>();
	protected LSModel m;

	/**
	 * Adds a hook.
	 * 
	 * @param hook
	 *            the hook to be added
	 */
	public void addHook(Hook hook) {
		hooks.add(hook);
	}

	/**
	 * Removes a hook
	 * 
	 * @param hook
	 *            the hook to be removed
	 */
	public void removeHook(Hook hook) {
		hooks.remove(hook);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see net.sf.javailp.Solver#solve(net.sf.javailp.Problem)
	 */
	public Result solve(Problem problem) {
		Map<LSExpression, Object> numToVar = new HashMap<LSExpression, Object>();
		Map<Object, LSExpression> varToNum = new HashMap<Object, LSExpression>();
		LocalSolver solver=new LocalSolver();
		
		try {
			
			m = solver.getModel();

			initWithParameters(solver);

			for (Object variable : problem.getVariables()) {
				VarType varType = problem.getVarType(variable);
				Number lowerBound = problem.getVarLowerBound(variable);
				Number upperBound = problem.getVarUpperBound(variable);

				LSExpression num;
				switch (varType) {
				case BOOL:
					num = m.boolVar();
					break;
				case INT:
					long lbl = (lowerBound != null ? lowerBound.longValue() : Long.MIN_VALUE+1);
					long ubl = (upperBound != null ? upperBound.longValue() : Long.MAX_VALUE-1);
					num = m.intVar(lbl, ubl);
					break;
				default: // REAL
					double lb = (lowerBound != null ? lowerBound.doubleValue() : -Double.MAX_VALUE);
					double ub = (upperBound != null ? upperBound.doubleValue() : Double.MAX_VALUE);
					num = m.floatVar(lb, ub);
					break;
				}				

				numToVar.put(num, variable);
				varToNum.put(variable, num);
				
				//System.out.println("Var: "+variable);
			}

			for (Constraint constraint : problem.getConstraints()) {
				Linear linear = constraint.getLhs();
				LSExpression lhs = convert(linear, varToNum);

				double rhs = constraint.getRhs().doubleValue();
				LSExpression constr = null;
				switch (constraint.getOperator()) {
				case LE:
					constr = (m.leq(lhs, rhs));
					break;
				case GE:
					constr = (m.geq(lhs, rhs));
					break;
				default: // EQ
					constr = (m.eq(lhs,  rhs));
				}
				//System.out.println("Constr: "+constr);
				m.addConstraint(constr);
			}

			if (problem.getObjective() != null) {
				Linear objective = problem.getObjective();
				LSExpression obj = convert(objective, varToNum);

				//System.out.println("Obj: "+obj);
				if (problem.getOptType() == OptType.MIN) {
					m.minimize(obj);
				} else {
					m.maximize(obj);
				}
			}

			for (Hook hook : hooks) {
				hook.call(m, varToNum);
			}
			
			m.close();
			
			solver.solve();
			LSSolution solution = solver.getSolution();

			switch (solution.getStatus()) {
			case Infeasible:
			case Inconsistent:
				return null;
			}
			
			final Result result;
			if (problem.getObjective() != null) {
				Linear objective = problem.getObjective();
				result = new ResultImpl(objective);
			} else {
				result = new ResultImpl();
			}

			for (Entry<Object, LSExpression> entry : varToNum.entrySet()) {
				Object variable = entry.getKey();
				LSExpression num = entry.getValue();
				VarType varType = problem.getVarType(variable);

				if (varType.isInt()) {
					int v = (int)solution.getIntValue(num);
					result.putPrimalValue(variable, v);
				} else {
					result.putPrimalValue(variable, solution.getDoubleValue(num));
				}
			}

			return result;

		} finally {
			solver.close();
		}
	}

	protected void initWithParameters(LocalSolver solver) {
		Object timeout = parameters.get(Solver.TIMEOUT);
		Object verbose = parameters.get(Solver.VERBOSE);

		if (timeout != null && timeout instanceof Number) {
			Number number = (Number) timeout;
			double value = number.doubleValue();
			solver.getParam().setTimeLimit((int)Math.ceil(value));
		}
		if (verbose != null && verbose instanceof Number) {
			Number number = (Number) verbose;
			int value = number.intValue();
			solver.getParam().setVerbosity(value);
		}

	}

	protected LSExpression convert(Linear linear, Map<Object, LSExpression> varToNum) {
		LSExpression linExp = m.createConstant(0);
		for (Term term : linear) {
			Number coeff = term.getCoefficient();
			Object variable = term.getVariable();			
			LSExpression mon = m.prod(m.createConstant(coeff.doubleValue()), varToNum.get(variable));
			linExp = m.sum(linExp, mon);
		}
		return linExp;
	}

}
