package soot.jimple.infoflow.android;

import heros.InterproceduralCFG;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import soot.Local;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.AssignStmt;
import soot.jimple.IdentityStmt;
import soot.jimple.IntConstant;
import soot.jimple.InvokeExpr;
import soot.jimple.ParameterRef;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;
import soot.jimple.infoflow.android.data.AndroidMethod;
import soot.jimple.infoflow.android.resources.ARSCFileParser;
import soot.jimple.infoflow.android.resources.ARSCFileParser.AbstractResource;
import soot.jimple.infoflow.android.resources.ARSCFileParser.ResPackage;
import soot.jimple.infoflow.android.resources.LayoutControl;
import soot.jimple.infoflow.source.MethodBasedSourceSinkManager;
import soot.jimple.toolkits.ide.icfg.BiDiInterproceduralCFG;

/**
 * SourceManager implementation for AndroidSources
 * 
 */
public class AndroidSourceSinkManager extends MethodBasedSourceSinkManager {
	
	private static final boolean FAST_MATCHING = true;
	
	/**
	 * Possible modes for matching layout components as data flow sources
	 * 
	 */
	public enum LayoutMatchingMode {
		/**
		 * Do not use Android layout components as sources
		 */
		NoMatch,
		
		/**
		 * Use all layout components as sources
		 */
		MatchAll,
		
		/**
		 * Only use sensitive layout components (e.g. password fields) as
		 * sources
		 */
		MatchSensitiveOnly
	}
	
	private final static String Activity_FindViewById =
			"<android.app.Activity: android.view.View findViewById(int)>";
	private final static String View_FindViewById =
			"<android.app.View: android.view.View findViewById(int)>";

	private final Set<AndroidMethod> sourceMethods;
	private final Set<AndroidMethod> sinkMethods;
	private final Set<AndroidMethod> callbackMethods;
	private final boolean weakMatching;
	
	private final LayoutMatchingMode layoutMatching;
	private final Map<Integer, LayoutControl> layoutControls;
	private List<ARSCFileParser.ResPackage> resourcePackages;
	
	private String appPackageName = "";
	
	/**
	 * Creates a new instance of the {@link AndroidSourceSinkManager} class with
	 * strong matching, i.e. the methods in the code must exactly match those
	 * in the list.
	 * @param sources The list of source methods 
	 * @param sinks The list of sink methods 
	 */
	public AndroidSourceSinkManager
			(Set<AndroidMethod> sources,
			Set<AndroidMethod> sinks) {
		this (sources, sinks, false);
	}
	
	/**
	 * Creates a new instance of the {@link AndroidSourceSinkManager} class with
	 * either strong or weak matching.
	 * @param sources The list of source methods
	 * @param sinks The list of sink methods
	 * @param weakMatching True for weak matching: If an entry in the list has
	 * no return type, it matches arbitrary return types if the rest of the
	 * method signature is compatible. False for strong matching: The method
	 * signature in the code exactly match the one in the list.
	 */
	public AndroidSourceSinkManager
			(Set<AndroidMethod> sources,
			Set<AndroidMethod> sinks,
			boolean weakMatching) {
		this(sources, sinks, new HashSet<AndroidMethod>(), weakMatching,
				LayoutMatchingMode.NoMatch, null);
	}

	/**
	 * Creates a new instance of the {@link AndroidSourceSinkManager} class with
	 * strong matching, i.e. the methods in the code must exactly match those
	 * in the list.
	 * @param sources The list of source methods 
	 * @param sinks The list of sink methods
	 * @param callbackMethods The list of callback methods whose parameters
	 * are sources through which the application receives data from the
	 * operating system
	 * @param weakMatching True for weak matching: If an entry in the list has
	 * no return type, it matches arbitrary return types if the rest of the
	 * method signature is compatible. False for strong matching: The method
	 * signature in the code exactly match the one in the list.
	 * @param layoutMatching Specifies whether and how to use Android layout
	 * components as sources for the information flow analysis
	 * @param layoutControls A map from reference identifiers to the respective
	 * Android layout controls
	 */
	public AndroidSourceSinkManager
			(Set<AndroidMethod> sources,
			Set<AndroidMethod> sinks,
			Set<AndroidMethod> callbackMethods,
			boolean weakMatching,
			LayoutMatchingMode layoutMatching,
			Map<Integer, LayoutControl> layoutControls) {
		this.sourceMethods = sources;
		this.sinkMethods = sinks;
		this.callbackMethods = callbackMethods;
		
		this.weakMatching = false;
		this.layoutMatching = layoutMatching;
		this.layoutControls = layoutControls;
		
		System.out.println("Created a SourceSinkManager with " + this.sourceMethods.size()
				+ " sources, " + this.sinkMethods.size() + " sinks, and "
				+ this.callbackMethods.size() + " callback methods.");
	}

	/**
	 * Checks whether the given method matches one of the methods from the list
	 * @param sMethod The method to check for a match
	 * @param aMethods The list of reference methods
	 * @return True if the given method matches an entry in the list, otherwise
	 * false
	 */
	private boolean matchesMethod(SootMethod sMethod, Set<AndroidMethod> aMethods) {
		if (FAST_MATCHING)
			return aMethods.contains(new AndroidMethod(sMethod));
		else {
			for (AndroidMethod am : aMethods) {
				if (!am.getClassName().equals(sMethod.getDeclaringClass().getName()))
					continue;
				if (!am.getMethodName().equals(sMethod.getName()))
					continue;
				if (am.getParameters().size() != sMethod.getParameterCount())
					continue;
				
				boolean matches = true;
				for (int i = 0; i < am.getParameters().size(); i++)
					if (!am.getParameters().get(i).equals(sMethod.getParameterType(i).toString())) {
						matches = false;
						break;
					}
				if (!matches)
					continue;
				
				if (!weakMatching)
					if (!am.getReturnType().isEmpty())
						if (!am.getReturnType().equals(sMethod.getReturnType().toString()))
							continue;
				
				return true;
			}
			return false;
		}
	}
	
	@Override
	public boolean isSourceMethod(SootMethod sMethod) {
		return this.matchesMethod(sMethod, this.sourceMethods);
	}

	@Override
	public boolean isSinkMethod(SootMethod sMethod) {
		return this.matchesMethod(sMethod, this.sinkMethods);
	}

	@Override
	public boolean isSource(Stmt sCallSite, InterproceduralCFG<Unit, SootMethod> cfg) {
		assert cfg instanceof BiDiInterproceduralCFG;
		
		// This might be a normal source method
		if (super.isSource(sCallSite, cfg))
			return true;
		// This call might read out sensitive data from the UI
		if (isUISource(sCallSite, cfg))
			return true;
		// This statement might access a sensitive parameter in a callback
		// method
		if (sCallSite instanceof IdentityStmt) {
			IdentityStmt is = (IdentityStmt) sCallSite;
			if (is.getRightOp() instanceof ParameterRef)
				for (AndroidMethod am : this.callbackMethods)
					if (am.getSignature().equals(cfg.getMethodOf(sCallSite).getSignature()))
						return true;
		}
		
		return false;
	}

	/**
	 * Checks whether the given call site indicates a UI source, e.g. a password
	 * input
	 * @param sCallSite The call site that may potentially read data from a
	 * sensitive UI control
	 * @param cfg The bidirectional control flow graph
	 * @return True if the given call site reads data from a UI source, false
	 * otherwise
	 */
	private boolean isUISource(Stmt sCallSite, InterproceduralCFG<Unit, SootMethod> cfg) {
		// If we match input controls, we need to check whether this is a call
		// to one of the well-known resource handling functions in Android
		if (this.layoutMatching != LayoutMatchingMode.NoMatch
				&& sCallSite.containsInvokeExpr()) {
			InvokeExpr ie = sCallSite.getInvokeExpr();
			if (ie.getMethod().getSignature().equals(Activity_FindViewById)
					|| ie.getMethod().getSignature().equals(View_FindViewById)) {
				// If we match all controls, we don't care about the specific
				// control we're dealing with
				if (this.layoutMatching == LayoutMatchingMode.MatchAll)
					return true;
				
				// If we match specific controls, we need to get the ID of
				// control and look up the respective data object
				if (ie.getArgCount() != 1) {
					System.err.println("Framework method call with unexpected "
							+ "number of arguments");
					return false;
				}
				int id = 0;
				if (ie.getArg(0) instanceof IntConstant)
					id = ((IntConstant) ie.getArg(0)).value;
				else if (ie.getArg(0) instanceof Local) {
					Integer idVal = findLastResIDAssignment(sCallSite, (Local)
							ie.getArg(0), (BiDiInterproceduralCFG<Unit, SootMethod>) cfg,
							new HashSet<Stmt>(cfg.getMethodOf(sCallSite).getActiveBody().getUnits().size()));
					if (idVal == null) {
						System.out.println(cfg.getMethodOf(sCallSite).getActiveBody().toString());
						return false;
					}
					else
						id = idVal.intValue();
				}
				else {
					System.err.println("Framework method call with unexpected "
							+ "parameter type: " + ie.toString() + ", "
							+ "first parameter is of type " + ie.getArg(0).getClass());
					return false;
				}
				
				LayoutControl control = this.layoutControls.get(id);
				if (control == null) {
					System.err.println("Layout control with ID " + id + " not found");
					return false;
				}
				if (this.layoutMatching == LayoutMatchingMode.MatchSensitiveOnly
						&& control.isSensitive())
 					return true;
			}
		}
		return false;
	}

	/**
	 * Finds the last assignment to the given local representing a resource ID
	 * by searching upwards from the given statement
	 * @param stmt The statement from which to look backwards
	 * @param local The variable for which to look for assignments
	 * @return The last value assigned to the given variable
	 */
	private Integer findLastResIDAssignment
			(Stmt stmt, Local local, BiDiInterproceduralCFG<Unit, SootMethod> cfg,
			Set<Stmt> doneSet) {
		if (!doneSet.add(stmt))
			return null;
		
		// If this is an assign statement, we need to check whether it changes
		// the variable we're looking for
		if (stmt instanceof AssignStmt) {
			AssignStmt assign = (AssignStmt) stmt;
			if (assign.getLeftOp() == local) {
				// ok, now find the new value from the right side
				if (assign.getRightOp() instanceof IntConstant)
					return ((IntConstant) assign.getRightOp()).value;
				
				if (assign.getRightOp() instanceof InvokeExpr) {
					InvokeExpr inv = (InvokeExpr) assign.getRightOp();
					if (inv.getMethod().getName().equals("getIdentifier")
							&& inv.getMethod().getDeclaringClass().getName().equals("android.content.res.Resources")
							&& this.resourcePackages != null) {
						// The right side of the assignment is a call into the well-known
						// Android API method for resource handling
						if (inv.getArgCount() != 3) {
							System.err.println("Invalid parameter count for call to getIdentifier");
							return null;
						}
						
						// Find the parameter values
						String resName = "";
						String resID = "";
						String packageName = "";
						
						// In the trivial case, these values are constants
						if (inv.getArg(0) instanceof StringConstant)
							resName = ((StringConstant) inv.getArg(0)).value;
						if (inv.getArg(1) instanceof StringConstant)
							resID = ((StringConstant) inv.getArg(1)).value;
						if (inv.getArg(2) instanceof StringConstant)
							packageName = ((StringConstant) inv.getArg(2)).value;
						else if (inv.getArg(2) instanceof Local)
							packageName = findLastStringAssignment(stmt, (Local) inv.getArg(2), cfg);
						else  {
							System.err.println("Unknown parameter type in call to getIdentifier");
							return null;
						}
												
						// Find the resource
						ARSCFileParser.AbstractResource res = findResource(resName, resID, packageName);
						if (res != null)
							return res.getResourceID();
					}
				}
			}
		}

		// Continue the search upwards
		for (Unit pred : cfg.getPredsOf(stmt)) {
			if (!(pred instanceof Stmt))
				continue;
			Integer lastAssignment = findLastResIDAssignment((Stmt) pred, local, cfg, doneSet);
			if (lastAssignment != null)
				return lastAssignment;
		}
		return null;
	}

	/**
	 * Finds the given resource in the given package
	 * @param resName The name of the resource to retrieve
	 * @param resID
	 * @param packageName The name of the package in which to look for the
	 * resource
	 * @return The specified resource if available, otherwise null
	 */
	private AbstractResource findResource
			(String resName,
			String resID,
			String packageName) {
		// Find the correct package
		for (ARSCFileParser.ResPackage pkg : this.resourcePackages) {
			// If we don't have any package specification, we pick the app's default package
			boolean matches = (packageName == null || packageName.isEmpty())
					&& pkg.getPackageName().equals(this.appPackageName);
			matches |= pkg.getPackageName().equals(packageName);
			if (!matches)
				continue;
			
			// We have found a suitable package, now look for the resource
			for (ARSCFileParser.ResType type : pkg.getDeclaredTypes())
				if (type.getTypeName().equals(resID)) {
					AbstractResource res = type.getFirstResource(resName);
					return res;
				}
		}
		return null;
	}

	/**
	 * Finds the last assignment to the given String local by searching upwards
	 * from the given statement
	 * @param stmt The statement from which to look backwards
	 * @param local The variable for which to look for assignments
	 * @return The last value assigned to the given variable
	 */
	private String findLastStringAssignment(Stmt stmt, Local local, BiDiInterproceduralCFG<Unit, SootMethod> cfg) {
		if (stmt instanceof AssignStmt) {
			AssignStmt assign = (AssignStmt) stmt;
			if (assign.getLeftOp() == local) {
				// ok, now find the new value from the right side
				if (assign.getRightOp() instanceof StringConstant)
					return ((StringConstant) assign.getRightOp()).value;
			}
		}
		
		// Continue the search upwards
		for (Unit pred : cfg.getPredsOf(stmt)) {
			if (!(pred instanceof Stmt))
				continue;
			String lastAssignment = findLastStringAssignment((Stmt) pred, local, cfg);
			if (lastAssignment != null)
				return lastAssignment;
		}
		return null;
	}

	/**
	 * Adds a list of methods as sinks
	 * @param sinks The methods to be added as sinks
	 */
	public void addSink(Set<AndroidMethod> sinks) {
		this.sinkMethods.addAll(sinks);
	}

	/**
	 * Sets the resource packages to be used for finding sensitive layout
	 * controls as sources
	 * @param resourcePackages The resource packages to be used for looking
	 * up layout controls
	 */
	public void setResourcePackages(List<ResPackage> resourcePackages) {
		this.resourcePackages = resourcePackages;
	}

	/**
	 * Sets the name of the app's base package
	 * @param appPackageName The name of the app's base package
	 */
	public void setAppPackageName(String appPackageName) {
		this.appPackageName = appPackageName;
	}

}
