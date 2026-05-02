import java.util.List;

import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.xtext.validation.Issue;
import org.omg.sysml.delegate.invocation.OperationInvocationDelegateFactory;
import org.omg.sysml.delegate.setting.DerivedPropertySettingDelegateFactory;
import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.interactive.SysMLInteractiveResult;
import org.omg.sysml.lang.sysml.Element;

public class ValidationProbe {
  static void installDelegates() {
    EStructuralFeature.Internal.SettingDelegate.Factory.Registry.INSTANCE.put(
      DerivedPropertySettingDelegateFactory.SYSML_ANNOTATION,
      new DerivedPropertySettingDelegateFactory()
    );
    EOperation.Internal.InvocationDelegate.Factory.Registry.INSTANCE.put(
      OperationInvocationDelegateFactory.SYSML_ANNOTATION,
      new OperationInvocationDelegateFactory()
    );
  }

  static String describe(Element element) {
    if (element == null) {
      return "(null)";
    }
    return element.eClass().getName()
      + " qn=" + element.getQualifiedName()
      + " declared=" + element.getDeclaredName()
      + " id=" + element.getElementId();
  }

  static void printIssues(String label, List<Issue> issues) {
    System.out.println(label + " count=" + (issues == null ? 0 : issues.size()));
    if (issues == null) {
      return;
    }
    for (Issue issue : issues) {
      System.out.println(
        "  " + issue.getSeverity()
          + " line=" + issue.getLineNumber()
          + " col=" + issue.getColumn()
          + " offset=" + issue.getOffset()
          + " len=" + issue.getLength()
          + " msg=" + issue.getMessage()
      );
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("Usage: ValidationProbe <libraryPath> <modelText>");
      System.exit(2);
    }
    String libraryPath = args[0];
    String modelText = args[1];

    installDelegates();

    SysMLInteractive sysml = SysMLInteractive.createInstance();
    sysml.loadLibrary(libraryPath);

    Element partsPart = sysml.resolve("Parts::Part");
    System.out.println("resolve Parts::Part => " + describe(partsPart));

    SysMLInteractiveResult result = sysml.process(modelText, false);
    System.out.println("result.ok=" + (!result.hasErrors() && result.getException() == null));
    System.out.println("result.root=" + describe(result.getRootElement()));
    if (result.getException() != null) {
      System.out.println("result.exception=" + result.formatException());
    }
    printIssues("syntax", result.getSyntaxErrors());
    printIssues("semantic", result.getSemanticErrors());
    printIssues("warnings", result.getWarnings());
    printIssues("all", result.getIssues());
  }
}
