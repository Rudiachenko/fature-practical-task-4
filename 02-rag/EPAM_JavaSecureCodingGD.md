# 1 INTRODUCTION

To minimize the likelihood of security vulnerabilities caused by programmer error, Java developers should adhere to the recommended secure coding guidelines.

This document includes EPAM guidelines for secure coding for Java, which combines the official guidelines provided by Oracle and also rules and recommendations that reflect the current thinking of the secure coding community.

The owner of this guideline is Head Delivery Management Office. The approver of this guideline is the Senior Director, Delivery Management Office on behalf of the Head Delivery Management Office. EPAM Java Competency Center is responsible for review of this guideline.

# 2 SECURE CODING GUIDELINES

## 2.1 CORE

### 2.1.1 Prefer to Have Obviously no Flaws Rather than no Obvious Flaws

Creating secure code is not necessarily easy. Despite the unusually robust nature of Java, flaws can slip past with surprising ease. Design and write code that does not require clever logic to see that it is safe. Specifically, follow the guidelines in this document unless there is a very strong reason not to.

### 2.1.2 Design APIs to Avoid Security Concerns

It is better to design APIs with security in mind. Trying to retrofit security into an existing API is more difficult and error prone. For example, making a class final prevents a malicious subclass from adding finalizers, cloning, and overriding random methods. Any use of the SecurityManager highlights an area that should be scrutinized.

### 2.1.3 Restrict Privileges

Despite best efforts, not all coding flaws will be eliminated even in well reviewed code. However, if the code is operating with reduced privileges, then exploitation of any flaws is likely to be thwarted. The most extreme form of this is known as the principle of least privilege. Using the Java security mechanism this can be implemented statically by restricting permissions through policy files and dynamically with the use of the java.security.AccessController.doPrivileged mechanism.

Rich Internet Applications (RIA) can specify their requested permissions via an applet parameter or in the JNLP. A signed jar can also include a manifest attribute that specifies whether it must run in a sandbox or with all permissions. If a sandboxed applet or application attempts to execute security-sensitive code, the JRE will throw a security exception. RIAs should follow the principle of least privilege, and should be configured to run with the least amount of necessary permissions. Running a RIA with all permissions should be avoided whenever possible.

### 2.1.4 Minimize the Number of Permission checks

Java is primarily an object-capability language. SecurityManager checks should be considered a last resort. Perform security checks at a few defined points and return an object (a capability) that client code retains so that no further permission checks are required.

### 2.1.5 Document Security-Related Information

API documentation should cover security-related information such as required permissions, security-related exceptions, caller sensitivity, and any preconditions or postconditions that are relevant to security. Documenting this information in comments for a tool such as JavaDoc can also help to ensure that it is kept up to date.

### 2.1.6 Don't Store Secrets

Don't store secrets (cryptographic keys, passwords, or algorithm) in the code or data. Hostile JVMs can quickly view this data. Code obfuscation doesn't really hide the code from serious attackers.

### 2.1.7 If You Must Sign Your Code, Put it all in One Archive

If you must sign your code, put it all in one archive file.

The goal of this rule is to prevent an attacker from carrying out a mix-and-match attack in which the attacker constructs a new applet or library that links some of your signed classes together with malicious classes, or links together signed classes that you never meant to be used together. By signing a group of classes together, you make this attack more difficult. Existing code-signing systems do an inadequate job of preventing mix-and-match attacks, so this rule cannot prevent such attacks completely. But using a single archive can't hurt.

## 2.2 OBJECTS

### 2.2.1 Never Return a Mutable Object to Potentially Malicious Code

Never return a mutable object to potentially malicious code (since the code may decide to change it). Note that arrays are mutable (even if the array contents aren't), so don't return a reference to an internal array with sensitive data.

### 2.2.2 Never Store User Given Mutable Objects

Never store user given mutable objects (including arrays of objects) directly. Otherwise, the user could hand the object to the secure code, let the secure code ''check'' the object, and change the data while the secure code was trying to use the data. Clone arrays before saving them internally, and be careful here (e.g., beware of user-written cloning routines).

### 2.2.3 Don't Depend on Initialization

Don't depend on initialization. There are several ways to allocate uninitialized objects.

### 2.2.4 Make Everything Final

Make everything final, unless there's a good reason not to. If a class or method is non-final, an attacker could try to extend it in a dangerous and unforeseen way. Note that this causes a loss of extensibility, in exchange for security.

### 2.2.5 Avoid Inner Classes

Avoid inner classes. When inner classes are translated into byte codes, the inner class is translated into a class accessible to any class in the package. Even worse, the enclosing class's private fields silently become non-private to permit access by the inner class!

### 2.2.6 Compare Classes and not Class Names

In a Java Virtual Machine (JVM), "Two classes are the same class (and consequently the same type) if they are loaded by the same class loader and they have the same fully qualified name". Two classes with the same name but different package names are distinct, as are two classes with the same fully qualified name loaded by different class loaders.

It could be necessary to check whether a given object has a specific class type or whether two objects have the same class type associated with them, for example, when implementing the equals() method. If the comparison is performed incorrectly, the code could assume that the two objects are of the same class when they are not. As a result, class names must not be compared.

Depending on the function that the insecure code performs, it could be vulnerable to a mix-and-match attack. An attacker could supply a malicious class with the same fully qualified name as the target class. If access to a protected resource is granted based on the comparison of class names alone, the unprivileged class could gain unwarranted access to the resource.

Conversely, the assumption that two classes deriving from the same codebase are the same is error prone. Although this assumption is commonly observed to be true in desktop applications, it is typically not the case with J2EE servlet containers. The containers can use different class loader instances to deploy and recall applications at runtime without having to restart the JVM. In such situations, two objects whose classes come from the same codebase could appear to the JVM to be two different classes. Also note that the equals() method might not return true when comparing objects originating from the same codebase.

### 2.2.7 Do not Use Public Static Nonfinal Fields

Client code can trivially access public static fields because access to such fields are not checked by a security manager. Furthermore, new values cannot be validated programmatically before they are stored in these fields.

In the presence of multiple threads, nonfinal public static fields can be modified in inconsistent.

Improper use of public static fields can also result in type-safety issues. For example, untrusted code can supply an unexpected subtype with malicious methods when the variable is defined to be of a more general type, such as java.lang.Object. As a result, classes must not contain nonfinal public static fields.

### 2.2.8 Prevent Constructors from Calling Methods that Can be Overridden

Constructors that call overridable methods give attackers a reference to this (the object being constructed) before the object has been fully initialized. Likewise, clone, readObject, or readObjectNoData methods that call overridable methods may do the same. The readObject methods will usually call java.io.ObjectInputStream.defaultReadObject, which is an overridable method.

### 2.2.9 Defend Against Cloning of non-Final Classes

A non-final class may be subclassed by a class that also implements java.lang.Cloneable. The result is that the base class can be unexpectedly cloned, although only for instances created by an adversary. The clone will be a shallow copy. The twins will share referenced objects but have different fields and separate intrinsic locks. The "pointer to implementation" approach provides a good defense.

## 2.3 INPUT AND OUTPUT

### 2.3.1 Validate Inputs

Input from untrusted sources must be validated before use. Maliciously crafted inputs may cause problems, whether coming through method arguments or external streams. Examples include overflow of integer values and directory traversal attacks by including "../" sequences in filenames. Ease-of- use features should be separated from programmatic interfaces. Note that input validation must occur after any defensive copying of that input.

### 2.3.2 Validate Output from Untrusted Objects as Input

In general method arguments should be validated but not return values. However, in the case of an upcall (invoking a method of higher level code) the returned value should be validated. Likewise, an object only reachable as an implementation of an upcall need not validate its inputs.

### 2.3.3 Define Wrappers Around Native Methods

Java code is subject to runtime checks for type, array bounds, and library usage. Native code, on the other hand, is generally not. While pure Java code is effectively immune to traditional buffer overflow attacks, native methods are not. To offer some of these protections during the invocation of native code, do not declare a native method public. Instead, declare it private and expose the functionality through a public Java-based wrapper method. A wrapper can safely perform any necessary input validation prior to the invocation of the native method:

```

	public final class NativeMethodWrapper {
		// private native method
		private native void nativeOperation(byte[] data, int offset, int len);
	
		// wrapper method performs checks
		public void doOperation(byte[] data, int offset, int len) {
			// copy mutable input
			data = data.clone();
		
			// validate input
			// Note offset+len would be subject to integer overflow.
			// For instance if offset = 1 and len = Integer.MAX_VALUE,
			// then offset+len == Integer.MIN_VALUE which is lower
			// than data.length.
			// Further,
			// loops of the form
			// for (int i=offset; i<offset+len; ++i) { ... }
			// would not throw an exception or cause native code to
			// crash.
			if (offset < 0 || len < 0 || offset > data.length - len) {
				throw new IllegalArgumentException();
			}
			nativeOperation(data, offset, len);
		}
	}

```

### 2.3.4 Normalize Strings before Validating them

Many applications that accept untrusted input strings employ input filtering and validation mechanisms based on the strings' character data. For example, an application's strategy for avoiding cross-site scripting (XSS) vulnerabilities may include forbidding `<script>` tags in inputs. Such blacklisting mechanisms are a useful part of a security strategy, even though they are insufficient for complete input validation and sanitization.

Character information in Java is based on the Unicode Standard. The following table shows the version of Unicode supported by the latest three releases of Java SE.
• Java SE 6 Unicode Standard, version 4.0 [Unicode 2003]
• Java SE 7 Unicode Standard, version 6.0.0 [Unicode 2011]
• Java SE 8 Unicode Standard, version 6.2.0 [Unicode 2012]

Applications that accept untrusted input should normalize the input before validating it. Normalization is important because in Unicode, the same string can have many different representations.

### 2.3.5 Do not Log Unsensitized User Input

A log injection vulnerability arises when a log entry contains unsensitized user input. A malicious user can insert fake log data and consequently deceive system administrators as to the system's behavior. For example, an attacker might split a legitimate log entry into two log entries by entering a carriage return and line feed (CRLF) sequence to mislead an auditor. Log injection attacks can be prevented by sanitizing and validating any untrusted input sent to a log.

Logging unsanitized user input can also result in leaking sensitive data across a trust boundary. For example, an attacker might inject a script into a log file such that when the file is viewed using a web browser, the browser could provide the attacker with a copy of the administrator's cookie so that the attacker might gain access as the administrator.

### 2.3.6 Do not Operate on Files in Shared Directories

Multiuser systems allow multiple users with different privileges to share a file system. Each user in such an environment must be able to determine which files are shared and which are private, and each user must be able to enforce these decisions.

Unfortunately, a wide variety of file system vulnerabilities can be exploited by an attacker to gain access to files for which they lack sufficient privileges, particularly when operating on files that reside in shared directories in which multiple users may create, move, or delete files. Privilege escalation is also possible when these programs run with elevated privileges. A number of file system properties and capabilities can be exploited by an attacker, including file links, device files, and shared file access. To prevent vulnerabilities, a program must operate only on files in secure directories.

A directory is secure with respect to a particular user if only the user and the system administrator are allowed to create, move, or delete files inside the directory. Furthermore, each parent directory must itself be a secure directory up to and including the root directory. On most systems, home or user directories are secure by default and only shared directories are insecure.

### 2.3.7 Do not Reset a Servlet’s Output Stream after Committing it

When a web servlet receives a request from a client, it must produce some suitable response. Java's HttpServlet provides the HttpServletResponse object to capture a suitable response. This response can be built using an output stream provided by getOutputStream() or a writer provided by getWriter().

A response is said to be committed if its status code and HTML headers have been sent. After a response is committed, further data may be added to the response, but certain behaviors become impossible. For example, it is impossible to change the character encoding, because the encoding is included in the HTML header. Some of these illegal operations will yield a IllegalStateException, while others will have no effect. These illegal behaviors include the following:
• Resetting the stream or recommitting to the stream;
• Flushing the stream's or writer's buffer;
• Invoking either getWriter() or getOutputStream();
• Redirecting an HttpServletResponse to another server;
• Modifying the stream's character encoding, content type, or buffer size.

## 2.4 ACCESSIBILITY

### 2.4.1 Limit the Accessibility of Classes, Interfaces, Methods, and Fields

A Java package comprises a grouping of related Java classes and interfaces. Declare any class or interface public if it is specified as part of a published API, otherwise, declare it package-private. Similarly, declare class members and constructors (nested classes, methods, or fields) public or protected as appropriate, if they are also part of the API. Otherwise, declare them private or package- private to avoid exposing the implementation. Note that members of interfaces are implicitly public. 

Classes loaded by different loaders do not have package-private access to one another even if they have the same package name. Classes in the same package loaded by the same class loader must either share the same code signing certificate or not have a certificate at all. In the Java virtual machine class loaders are responsible for defining packages. It is recommended that, as a matter of course, packages are marked as sealed in the jar file manifest.

### 2.4.2 Limit the Accessibility of Packages

Containers may hide implementation code by adding to the package.access security property. This property prevents untrusted classes from other class loaders linking and using reflection on the specified package hierarchy. Care must be taken to ensure that packages cannot be accessed by untrusted contexts before this property has been set.

This example code demonstrates how to append to the package.access security property. Note that it is not thread-safe. This code should generally only appear once in a system.

```

	private static final String PACKAGE_ACCESS_KEY = "package.access";
	static {
		String packageAccess = java.security.Security.getProperty(PACKAGE_ACCESS_KEY);
	
		java.security.Security.setProperty(
			PACKAGE_ACCESS_KEY,
			(
				(packageAccess == null || packageAccess.trim().isEmpty()) ? "" : (packageAccess + ",")
			) + "xx.example.product.implementation."
		);
	}

```

### 2.4.3 Isolate Unrelated Code

Containers, that is to say code that manages code with a lower level of trust, should isolate unrelated application code. Even otherwise untrusted code is typically given permissions to access its origin, and therefore untrusted code from different origins should be isolated. The Java Plugin, for example, loads unrelated applets into separate class loader instances and runs them in separate thread groups.

Although there may be security checks on direct accesses, there are indirect ways of using the system class loader and thread context class loader. Programs should be written with the expectation that the system class loader is accessible everywhere and the thread context class loader is accessible to all code that can execute on the relevant threads.

Some apparently global objects are actually local to applet or application contexts. Applets loaded from different web sites will have different values returned from, for example, java.awt.Frame.getFrames. Such static methods (and methods on true globals) use information from the current thread and the class loaders of code on the stack to determine which is the current context. This prevents malicious applets from interfering with applets from other sites.

Mutable statics and exceptions are common ways that isolation is inadvertently breached. Mutable statics allow any code to interfere with code that directly or, more likely, indirectly uses them.

Library code can be carefully written such that it is safely usable by less trusted code. Libraries require a level of trust at least equal to the code it is used by in order not to violate the integrity of the client code. Containers should ensure that less trusted code is not able to replace more trusted library code and does not have package-private access. Both restrictions are typically enforced by using a separate class loader instance, the library class loader a parent of the application class loader.

### 2.4.4 Limit Exposure of ClassLoader Instances

Access to ClassLoader instances allows certain operations that may be undesirable:
• Access to classes that client code would not normally be able to access;
• Retrieve information in the URLs of resources (actually opening the URL is limited with the usual restrictions);
• Assertion status may be turned on and off;
• The instance may be cast to a subclass. ClassLoader subclasses frequently have undesirable methods.

### 2.4.5 Don't Depend on Package Scope

Don't depend on package scope for security. A few classes, such as java.lang, are closed by default,
and some Java Virtual Machines (JVMs) let you close off other packages. Otherwise, Java classes are
not closed. Thus, an attacker could introduce a new class inside your package, and use this new class
to access the things you thought you were protecting.

### 2.4.6 Do not Use Public Fields

Do not use public fields or variables; declare them as private and provide access methods to them so you can limit their accessibility.

### 2.4.7 Make Methods Private

Make methods private unless there is a good reason to do otherwise (and if you do otherwise, document why). These non-private methods must protect themselves, because they may receive tainted data (unless you've somehow arranged to protect them).

### 2.4.8 Avoid Using Static Field

Avoid using static field variables. Such variables are attached to the class (not class instances), and classes can be located by any other class. As a result, static field variables can be found by any other class, making them much more difficult to secure.

## 2.5 SERIALIZATION

### 2.5.1 Avoid Serialization for Security-Sensitive Classes

Security-sensitive classes that are not serializable will not have the problems detailed in this section. Making a class serializable effectively creates a public interface to all fields of that class. Serialization also effectively adds a hidden public constructor to a class, which needs to be considered when trying to restrict object construction.

Similarly, lambdas should be scrutinized before being made serializable. Functional interfaces should not be made serializable without due consideration for what could be exposed.

### 2.5.2 Guard Sensitive Data During Serialization

Once an object has been serialized the Java language's access controls can no longer be enforced and attackers can access private fields in an object by analyzing its serialized byte stream. Therefore, do not serialize sensitive data in a serializable class.

Approaches for handling sensitive fields in serializable classes are:
• Declare sensitive fields transient;
• Define the serialPersistentFields array field appropriately;
• Implement writeObject and use ObjectOutputStream.putField selectively;
• Implement writeReplace to replace the instance with a serial proxy;
• Implement the Externalizable interface.

### 2.5.3 View Deserialization the Same as Object Construction

Deserialization creates a new instance of a class without invoking any constructor on that class. Therefore, deserialization should be designed to behave like normal construction.

Default deserialization and ObjectInputStream.defaultReadObject can assign arbitrary objects to non-transient fields and does not necessarily return. Use ObjectInputStream.readFields instead to insert copying before assignment to fields. Or, if possible, don't make sensitive classes serializable.

```

	public final class ByteString implements java.io.Serializable {
		private static final long serialVersionUID = 1L;
		private byte[] data;
	
		public ByteString(byte[] data) {
			this.data = data.clone(); // Make copy before assignment.
		}

		private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
			java.io.ObjectInputStreadm.GetField fields = in.readFields();
			this.data = ((byte[])fields.get("data")).clone();
		}
		...
	}

```

Perform the same input validation checks in a readObject method implementation as those performed in a constructor. Likewise, assign default values that are consistent with those assigned in a constructor to all fields, including transient fields, which are not explicitly set during deserialization.

In addition create copies of deserialized mutable objects before assigning them to internal fields in a readObject implementation. This defends against hostile code deserializing byte streams that are specially crafted to give the attacker references to mutable objects inside the deserialized container object.

```

	public final class Nonnegative implements java.io.Serializable {
		private static final long serialVersionUID = 1L;
		private int value;
	
		public Nonnegative(int value) {
			// Make check before assignment.
			this.data = nonnegative(value);
		}

		private static int nonnegative(int value) {
			if (value < 0) {
				throw new IllegalArgumentException(value + " is negative");
			}
			return value;
		}
	
		private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
			java.io.ObjectInputStreadm.GetField fields = in.readFields();
			this.value = nonnegative(field.get(value, 0));
		}
		...
	}
	
```

Attackers can also craft hostile streams in an attempt to exploit partially initialized (deserialized) objects. Ensure a serializable class remains totally unusable until deserialization completes successfully. For example, use an initialized flag. Declare the flag as a private transient field and only set it in a readObject or readObjectNoData method (and in constructors) just prior to returning successfully. All public and protected methods in the class must consult the initialized flag before proceeding with their normal logic. As discussed earlier, use of an initialized flag can be cumbersome. Simply ensuring that all fields contain a safe value (such as null) until deserialization successfully completes can represent a reasonable alternative.

### 2.5.4 Understand the Security Permissions Given to Serialization and Deserialization

Permissions appropriate for deserialization should be carefully checked. Additionally, deserialization of untrusted data should generally be avoided whenever possible.

Serialization with full permissions allows permission checks in writeObject methods to be circumvented. For instance, java.security.GuardedObject checks the guard before serializing the target object. With full permissions, this guard can be circumvented and the data from the object (although not the object itself) made available to the attacker.

Deserialization is more significant. A number of readObject implementations attempt to make security checks, which will pass if full permissions are granted. Further, some non-serializable security- sensitive, subclassable classes have no-argument constructors, for instance ClassLoader. Consider a malicious serializable class that subclasses ClassLoader. During deserialization the serialization method calls the constructor itself and then runs any readObject in the subclass. When the ClassLoader constructor is called no unprivileged code is on the stack, hence security checks will pass. Thus, don't deserialize with permissions unsuitable for the data. Instead, data should be deserialized with the least necessary privileges.

### 2.5.5 Do not Serialize Instances of Inner Classes

"An inner class is a nested class that is not explicitly or implicitly declared static". Serialization of inner classes (including local and anonymous classes) is error prone. According to the Serialization Specification:
• Serializing an inner class declared in a non-static context that contains implicit non-transient references to enclosing class instances results in serialization of its associated outer class instance.
• Synthetic fields generated by Java compilers to implement inner classes are implementation dependent and may vary between compilers; differences in such fields can disrupt compatibility as well as result in conflicting default serialVersionUID values. The names assigned to local and anonymous inner classes are also implementation dependent and may differ between compilers.
• Because inner classes cannot declare static members other than compile-time constant fields, they cannot use the serialPersistentFields mechanism to designate serializable fields.
• Because inner classes associated with outer instances do not have zero-argument constructors (constructors of such inner classes implicitly accept the enclosing instance as a prepended parameter), they cannot implement Externalizable. The Externalizable interface requires the implementing object to manually save and restore its state using the writeExternal() and readExternal() methods.

Consequently, programs must not serialize inner classes.

Because none of these issues apply to static member classes, serialization of static member classes is permitted.

## 2.6 CONCURRENCY

### 2.6.1 Do not Invoke Thread.run()

Thread startup can be misleading because the code can appear to be performing its function correctly when it is actually being executed by the wrong thread. Invoking the Thread.start() method instructs the Java runtime to start executing the thread's run() method using the started thread. Invoking a Thread object's run() method directly is incorrect. When a Thread object's run() method is invoked directly, the statements in the run() method are executed by the current thread rather than by the newly created thread. Furthermore, if the Thread object was constructed by instantiating a subclass of Thread that fails to override the run() method rather than constructed from a Runnable object, any calls to the subclass's run() method would invoke Thread.run(), which does nothing. Consequently, programs must not directly invoke a Thread object's run() method.

### 2.6.2 Use Thread Pools to Enable Graceful Degradation of Service During Traffic Bursts

Many programs must address the problem of handling a series of incoming requests. One simple concurrency strategy is the Thread-Per-Message design pattern, which uses a new thread for each request. This pattern is generally preferred over sequential executions of time-consuming, I/O-bound, session-based, or isolated tasks.

However, the pattern also introduces overheads not seen in sequential execution, including the time and resources required for thread creation and scheduling, for task processing, for resource allocation and deallocation, and for frequent context switching. Furthermore, an attacker can cause a denial of service (DoS) by overwhelming the system with too many requests at once, causing the system to become unresponsive rather than degrading gracefully. From a safety perspective, one component can exhaust all resources because of an intermittent error, consequently starving all other components.

Thread pools allow a system to limit the maximum number of simultaneous requests that it processes to a number that it can comfortably serve rather than terminating all services when presented with a deluge of requests. Thread pools overcome these issues by controlling the maximum number of worker threads that can execute concurrently. Each object that supports thread pools accepts a Runnable or Callable<T> task and stores it in a temporary queue until resources become available. Additionally, thread life-cycle management overhead is minimized because the threads in a thread pool can be reused and can be efficiently added to or removed from the pool.

Programs that use multiple threads to service requests should—and programs that may be subjected to DoS attacks must—ensure graceful degradation of service during traffic bursts. Use of thread pools is one acceptable approach to meeting this requirement.

### 2.6.3 Do not Override Thread-Safe Methods with Methods that are not Thread-Safe

Overriding thread-safe methods with methods that are unsafe for concurrent use can result in improper synchronization when a client that depends on the thread-safety promised by the parent inadvertently operates on an instance of the subclass. For example, an overridden synchronized method's contract can be violated when a subclass provides an implementation that is unsafe for concurrent use. Such overriding can easily result in errors that are difficult to diagnose. Consequently, programs must not override thread-safe methods with methods that are unsafe for concurrent use.

The locking strategy of classes designed for inheritance should always be documented. This information can subsequently be used to determine an appropriate locking strategy for subclasses.

### 2.6.4 Do not Use Background Threads During Class Initialization

Starting and using background threads during class initialization can result in class initialization cycles and deadlock. For example, the main thread responsible for performing class initialization can block waiting for the background thread, which in turn will wait for the main thread to finish class initialization. This issue can arise, for example, when a database connection is established in a background thread during class initialization. Consequently, programs must ensure that class initialization is complete before starting any threads.

