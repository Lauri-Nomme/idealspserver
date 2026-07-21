package typehierarchy;

public class TypeHierarchyTest {
    public String getName() {
        return "test";
    }
}

abstract class AbstractBase {
    public abstract void doSomething();
}

class ConcreteImpl extends AbstractBase {
    @Override
    public void doSomething() {
        System.out.println("done");
    }
}

interface MyInterface {
    void myMethod();
}

class ImplementsInterface implements MyInterface {
    @Override
    public void myMethod() {
        System.out.println("impl");
    }
}
