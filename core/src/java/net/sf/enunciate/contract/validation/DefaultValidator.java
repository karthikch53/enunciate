package net.sf.enunciate.contract.validation;

import com.sun.mirror.declaration.*;
import com.sun.mirror.type.DeclaredType;
import com.sun.mirror.type.InterfaceType;
import com.sun.mirror.type.TypeMirror;
import com.sun.mirror.type.VoidType;
import net.sf.enunciate.contract.jaxb.*;
import net.sf.enunciate.contract.jaxb.types.KnownXmlType;
import net.sf.enunciate.contract.jaxb.types.XmlClassType;
import net.sf.enunciate.contract.jaxb.types.XmlTypeMirror;
import net.sf.enunciate.contract.jaxws.*;
import net.sf.jelly.apt.decorations.declaration.DecoratedMethodDeclaration;
import net.sf.jelly.apt.decorations.declaration.PropertyDeclaration;
import net.sf.jelly.apt.decorations.type.DecoratedTypeMirror;

import javax.jws.WebService;
import javax.jws.soap.SOAPBinding;
import javax.xml.bind.annotation.*;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import javax.xml.namespace.QName;
import javax.xml.ws.Holder;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.TreeSet;

/**
 * Default validator.
 *
 * @author Ryan Heaton
 */
public class DefaultValidator implements Validator {

  public ValidationResult validateEndpointInterface(EndpointInterface ei) {
    ValidationResult result = new ValidationResult();

    Declaration delegate = ei.getDelegate();

    WebService ws = delegate.getAnnotation(WebService.class);
    if (ws == null) {
      result.addError(delegate.getPosition(), "Not an endpoint interface: no WebService annotation");
    }
    else {
      if ((ei.getPackage() == null) && (ei.getTargetNamespace() == null)) {
        result.addError(delegate.getPosition(), "An endpoint interface in no package must specify a target namespace.");
      }

      if ((ws.endpointInterface() != null) && (!"".equals(ws.endpointInterface()))) {
        result.addError(delegate.getPosition(), "Not an endpoint interface (it references another endpoint interface).");
      }
    }

    if (delegate instanceof AnnotationTypeDeclaration) {
      result.addError(delegate.getPosition(), "Annotation types are not valid endpoint interfaces.");
    }

    if (delegate instanceof EnumDeclaration) {
      result.addError(delegate.getPosition(), "Enums cannot be endpoint interfaces.");
    }

    TreeSet<WebMethod> uniquelyNamedWebMethods = new TreeSet<WebMethod>();
    for (WebMethod webMethod : ei.getWebMethods()) {
      if (!uniquelyNamedWebMethods.add(webMethod)) {
        result.addError(webMethod.getPosition(), "Web methods must have unique operation names.  Use annotations to disambiguate.");
      }
    }

    for (WebMethod webMethod : ei.getWebMethods()) {
      result.aggregate(validateWebMethod(webMethod));
    }
    for (EndpointImplementation implementation : ei.getEndpointImplementations()) {
      result.aggregate(validateEndpointImplementation(implementation));
    }

    return result;
  }

  public ValidationResult validateEndpointImplementation(EndpointImplementation impl) {
    ValidationResult result = new ValidationResult();
    Declaration delegate = impl.getDelegate();

    WebService ws = delegate.getAnnotation(WebService.class);
    if (ws == null) {
      result.addError(delegate.getPosition(), "Not an endpoint implementation (no WebService annotation).");
    }

    if (delegate instanceof EnumDeclaration) {
      result.addError(delegate.getPosition(), "An enum cannot be an endpoint implementation.");
    }

    if (!isAssignable((TypeDeclaration) delegate, (InterfaceDeclaration) impl.getEndpointInterface().getDelegate())) {
      result.addError(delegate.getPosition(), "Class does not implement its endpoint interface!");
    }

    return result;
  }

  /**
   * Whether declaration1 is assignable to declaration2.
   *
   * @return Whether declaration1 is assignable to declaration2.
   */
  protected boolean isAssignable(TypeDeclaration declaration1, InterfaceDeclaration declaration2) {
    String iffqn = declaration2.getQualifiedName();
    if (declaration1.getQualifiedName().equals(iffqn)) {
      return true;
    }

    Collection<InterfaceType> superinterfaces = declaration1.getSuperinterfaces();
    for (InterfaceType interfaceType : superinterfaces) {
      InterfaceDeclaration declaration = interfaceType.getDeclaration();
      if ((declaration != null) && (isAssignable(declaration, declaration2))) {
        return true;
      }
    }

    return false;
  }

  public ValidationResult validateWebMethod(WebMethod webMethod) {
    ValidationResult result = new ValidationResult();
    if (!webMethod.getModifiers().contains(Modifier.PUBLIC)) {
      result.addError(webMethod.getPosition(), "A non-public method cannot be a web method.");
    }

    javax.jws.WebMethod annotation = webMethod.getAnnotation(javax.jws.WebMethod.class);
    if ((annotation != null) && (annotation.exclude())) {
      result.addError(webMethod.getPosition(), "A method marked as excluded cannot be a web method.");
    }

    if (webMethod.isOneWay()) {
      if (!(webMethod.getReturnType() instanceof VoidType)) {
        result.addError(webMethod.getPosition(), "A method cannot be one-way if it doesn't return void.");
      }

      if (webMethod.getThrownTypes().size() > 0) {
        result.addError(webMethod.getPosition(), "A method cannot be one-way if it throws any exceptions.");
      }
    }

    SOAPBinding.ParameterStyle parameterStyle = webMethod.getSoapParameterStyle();
    if (parameterStyle == SOAPBinding.ParameterStyle.BARE) {
      //make sure the conditions of a BARE web method are met according to the spec, section 3.6.2.2
      if (webMethod.getSoapBindingStyle() != SOAPBinding.Style.DOCUMENT) {
        result.addError(webMethod.getPosition(), String.format("%s: a %s-style web method cannot have a parameter style of %s",
                                                               webMethod.getPosition(), webMethod.getSoapBindingStyle(), parameterStyle));
      }

      int inParams = 0;
      int outParams = 0;
      for (WebParam webParam : webMethod.getWebParameters()) {
        DecoratedTypeMirror paramType = ((DecoratedTypeMirror) webParam.getType());
        if (paramType.isArray()) {
          result.addError(webMethod.getPosition(), "A BARE web method must not have an array as a parameter.");
        }

        if (!webParam.isHeader()) {
          javax.jws.WebParam.Mode mode = webParam.getMode();

          if ((mode == javax.jws.WebParam.Mode.IN) || (mode == javax.jws.WebParam.Mode.INOUT)) {
            inParams++;
          }

          if ((mode == javax.jws.WebParam.Mode.OUT) || (mode == javax.jws.WebParam.Mode.INOUT)) {
            outParams++;
          }
        }
      }

      if (inParams > 1) {
        result.addError(webMethod.getPosition(), "A BARE web method must have at most 1 in or in/out non-header parameter.");
      }

      if (webMethod.getReturnType() instanceof VoidType) {
        if (outParams > 1) {
          result.addError(webMethod.getPosition(), "A BARE web method that returns void must have at most 1 out or in/out non-header parameter.");
        }
      }
      else {
        if (outParams > 0) {
          result.addError(webMethod.getPosition(), "A BARE web method that doesn't return void must have no out or in/out parameters.");
        }
      }
    }

    result.aggregate(validateWebResult(webMethod.getWebResult()));
    for (WebParam webParam : webMethod.getWebParameters()) {
      result.aggregate(validateWebParam(webParam));
    }
    for (WebFault webFault : webMethod.getWebFaults()) {
      result.aggregate(validateWebFault(webFault));
    }
    for (WebMessage webMessage : webMethod.getMessages()) {
      if (webMessage instanceof RequestWrapper) {
        result.aggregate(validateRequestWrapper((RequestWrapper) webMessage));
      }
      if (webMessage instanceof ResponseWrapper) {
        result.aggregate(validateResponseWrapper((ResponseWrapper) webMessage));
      }
    }

    return result;
  }

  public ValidationResult validateRequestWrapper(RequestWrapper requestWrapper) {
    ValidationResult result = new ValidationResult();
    WebMethod webMethod = requestWrapper.getWebMethod();
    if (webMethod.getSoapParameterStyle() == SOAPBinding.ParameterStyle.BARE) {
      result.addError(webMethod.getPosition(), "A BARE web method shouldn't have a request wrapper.");
    }

    javax.xml.ws.RequestWrapper annotation = webMethod.getAnnotation(javax.xml.ws.RequestWrapper.class);
    if ((annotation != null) && (annotation.targetNamespace() != null) && (!"".equals(annotation.targetNamespace()))) {
      if (!webMethod.getDeclaringEndpointInterface().getTargetNamespace().equals(annotation.targetNamespace())) {
        result.addError(webMethod.getPosition(), "Enunciate doesn't allow declaring a target namespace for a request wrapper that is different " +
          "from the target namespace of the endpoint interface.  If you really must, declare the parameter style BARE and use an xml root element from " +
          "another namespace for the parameter.");
      }
    }

    return result;
  }

  public ValidationResult validateResponseWrapper(ResponseWrapper responseWrapper) {
    ValidationResult result = new ValidationResult();
    WebMethod webMethod = responseWrapper.getWebMethod();
    if (webMethod.getSoapParameterStyle() == SOAPBinding.ParameterStyle.BARE) {
      result.addError(webMethod.getPosition(), "A BARE web method shouldn't have a response wrapper.");
    }

    if (webMethod.isOneWay()) {
      result.addError(webMethod.getPosition(), "A one-way method cannot have a response wrapper.");
    }


    javax.xml.ws.ResponseWrapper annotation = webMethod.getAnnotation(javax.xml.ws.ResponseWrapper.class);
    if ((annotation != null) && (annotation.targetNamespace() != null) && (!"".equals(annotation.targetNamespace()))) {
      String targetNamespace = webMethod.getDeclaringEndpointInterface().getTargetNamespace();
      if (!targetNamespace.equals(annotation.targetNamespace())) {
        result.addError(webMethod.getPosition(), "Enunciate doesn't allow declaring a target namespace for a response wrapper that is " +
          "different from the target namespace of the endpoint interface.  If you really must, declare the parameter style BARE and use an xml root " +
          "element from another namespace for the return value.");
      }
    }

    return result;
  }

  public ValidationResult validateWebParam(WebParam webParam) {
    ValidationResult result = new ValidationResult();
    DecoratedTypeMirror parameterType = (DecoratedTypeMirror) webParam.getType();
    if (parameterType.isInstanceOf(Holder.class.getName())) {
      result.addError(webParam.getPosition(), "Enunciate currently doesn't support in/out parameters.  Maybe someday...");
    }

    javax.jws.WebParam annotation = webParam.getAnnotation(javax.jws.WebParam.class);
    if ((annotation != null) && (!"".equals(annotation.targetNamespace()))) {
      String targetNamespace = webParam.getWebMethod().getDeclaringEndpointInterface().getTargetNamespace();
      if (!annotation.targetNamespace().equals(targetNamespace)) {
        result.addError(webParam.getPosition(), "Enunciate doesn't allow declaring a target namespace for a web parameter that is different from the " +
          "target namespace of the endpoint interface.  If you really want to, declare the parameter style BARE and use an xml root element from another " +
          "namespace for the parameter.");
      }
    }

    return result;
  }

  public ValidationResult validateWebResult(WebResult webResult) {
    ValidationResult result = new ValidationResult();
    WebMethod webMethod = webResult.getWebMethod();
    String targetNamespace = webMethod.getDeclaringEndpointInterface().getTargetNamespace();
    if (!targetNamespace.equals(webResult.getTargetNamespace())) {
      result.addError(webMethod.getPosition(), "Enunciate doesn't allow methods to return a web result with a target namespace that is " +
        "declared different from the target namespace of its endpoint interface.  If you really want to, declare the parameter style BARE and use " +
        "an xml root element from another namespace for the return value.");
    }

    return result;
  }

  public ValidationResult validateWebFault(WebFault webFault) {
    return new ValidationResult();
  }

  // Inherited.
  public ValidationResult validateComplexType(ComplexTypeDefinition complexType) {
    ValidationResult result = validateTypeDefinition(complexType);

    XmlTypeMirror baseType = complexType.getBaseType();
    while ((baseType instanceof XmlClassType) && (((XmlClassType) baseType).getTypeDefinition() instanceof ComplexTypeDefinition)) {
      ComplexTypeDefinition superType = (ComplexTypeDefinition) ((XmlClassType) baseType).getTypeDefinition();
      baseType = superType.getBaseType();

      if (superType.getValue() != null) {
        result.addError(complexType.getPosition(), "A complex type cannot subclass another complex type (" + superType.getQualifiedName() +
          ") that has an xml value.");
      }
    }

    if (complexType.getValue() != null) {
      if (!complexType.getElements().isEmpty()) {
        result.addError(complexType.getPosition(), "A type definition cannot have both an xml value and elements.");
      }
      else if (complexType.getAttributes().isEmpty()) {
        result.addError(complexType.getPosition(), "Should be a simple type, not a complex type.");
      }
    }

    return result;
  }

  // Inherited.
  public ValidationResult validateSimpleType(SimpleTypeDefinition simpleType) {
    ValidationResult result = validateTypeDefinition(simpleType);

    XmlTypeMirror baseType = simpleType.getBaseType();
    if (baseType == null) {
      result.addError(simpleType.getPosition(), "No base type specified.");
    }
    else if ((baseType instanceof XmlClassType) && (((XmlClassType) baseType).getTypeDefinition() instanceof ComplexTypeDefinition)) {
      result.addError(simpleType.getPosition(), "A simple type must have a simple base type. " + new QName(baseType.getNamespace(), baseType.getName())
        + " is a complex type.");
    }


    return result;
  }

  public ValidationResult validateEnumType(EnumTypeDefinition enumType) {
    return validateSimpleType(enumType);
  }

  /**
   * Validation logic common to all type definitions.
   *
   * @param typeDef The type definition to validate.
   */
  public ValidationResult validateTypeDefinition(TypeDefinition typeDef) {
    ValidationResult result = validatePackage(typeDef.getSchema());

    if (isXmlTransient(typeDef)) {
      result.addError(typeDef.getPosition(), "XmlTransient type definition.");
    }

    XmlType xmlType = typeDef.getAnnotation(XmlType.class);

    boolean needsNoArgConstructor = (!(typeDef instanceof EnumTypeDefinition));
    if (xmlType != null) {
      if ((typeDef.getDeclaringType() != null) && (!typeDef.getModifiers().contains(Modifier.STATIC))) {
        result.addError(typeDef.getPosition(), "An xml type must be either a top-level class or a nested static class.");
      }

      Class factoryClass = xmlType.factoryClass();
      String factoryMethod = xmlType.factoryMethod();

      if ((factoryClass != XmlType.DEFAULT.class) || (!"".equals(factoryMethod))) {
        needsNoArgConstructor = false;
        try {
          Method method = factoryClass.getMethod(factoryMethod);
          if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            //todo: is this really a requirement?
            result.addError(typeDef.getPosition(), "'" + factoryMethod + "' must be a static, no-arg method on '" + factoryClass.getName() + "'.");
          }
        }
        catch (NoSuchMethodException e) {
          result.addError(typeDef.getPosition(), "Unknown factory method '" + factoryMethod + "' on class '" + factoryClass.getName() + "'.");
        }
      }
      else if (typeDef.getAnnotation(XmlJavaTypeAdapter.class) != null) {
        needsNoArgConstructor = false;
        //todo: validate that this is a valid type adapter?
      }

      String[] propOrder = xmlType.propOrder();
      if ((propOrder.length > 0) && (!"".equals(propOrder[0]))) {
        //todo: validate that all properties and fields are accounted for in the propOrder list.
      }
    }

    if (needsNoArgConstructor) {
      //check for a zero-arg constructor...
      boolean hasNoArgConstructor = false;
      Collection<ConstructorDeclaration> constructors = typeDef.getConstructors();
      for (ConstructorDeclaration constructor : constructors) {
        if ((constructor.getModifiers().contains(Modifier.PUBLIC)) && (constructor.getParameters().size() == 0)) {
          hasNoArgConstructor = true;
          break;
        }
      }

      if (!hasNoArgConstructor) {
        result.addError(typeDef.getPosition(), "A TypeDefinition must have a public no-arg constructor or be annotated with a factory method.");
      }
    }

    if (typeDef.getValue() != null) {
      result.aggregate(validateValue(typeDef.getValue()));

      if (!typeDef.getElements().isEmpty()) {
        result.addError(typeDef.getValue().getPosition(), "A type definition cannot have both an xml value and a child element.");
      }
    }

    return result;
  }

  public ValidationResult validateRootElement(RootElementDeclaration rootElementDeclaration) {
    return new ValidationResult();
  }

  /**
   * Whether a declaration is xml transient.
   *
   * @param declaration The declaration on which to determine xml transience.
   * @return Whether a declaration is xml transient.
   */
  protected boolean isXmlTransient(Declaration declaration) {
    return (declaration.getAnnotation(XmlTransient.class) != null);
  }

  public ValidationResult validatePackage(Schema schema) {
    ValidationResult result = new ValidationResult();

    XmlSchemaType schemaType = schema.getAnnotation(XmlSchemaType.class);
    if (schemaType != null) {
      if (schemaType.type() == XmlSchemaType.DEFAULT.class) {
        result.addError(schema.getPosition(), "A type must be specified at the package-level for @XmlSchemaType.");
      }
    }

    XmlSchemaTypes schemaTypes = schema.getAnnotation(XmlSchemaTypes.class);
    if (schemaTypes != null) {
      for (XmlSchemaType xmlSchemaType : schemaTypes.value()) {
        if (xmlSchemaType.type() == XmlSchemaType.DEFAULT.class) {
          result.addError(schema.getPosition(), "A type must be specified at the package-level for all types of @XmlSchemaTypes.");
        }
      }
    }

    return result;
  }

  public ValidationResult validateAttribute(Attribute attribute) {
    ValidationResult result = validateAccessor(attribute);

    XmlTypeMirror baseType = attribute.getBaseType();
    if (baseType == null) {
      result.addError(attribute.getPosition(), "No base type specified.");
    }
    else if ((baseType instanceof XmlClassType) && (((XmlClassType) baseType).getTypeDefinition() instanceof ComplexTypeDefinition)) {
      result.addError(attribute.getPosition(), "A simple type must have a simple base type. " + new QName(baseType.getNamespace(), baseType.getName())
        + " is a complex type.");
    }

    return result;
  }

  public ValidationResult validateElement(Element element) {
    ValidationResult result = validateAccessor(element);

    XmlElements xmlElements = element.getAnnotation(XmlElements.class);
    if ((element.isCollectionType()) && (element.getBaseType() != KnownXmlType.ANY_TYPE) &&
      (xmlElements != null) && (xmlElements.value() != null) && (xmlElements.value().length > 1)) {
      result.addError(element.getPosition(),
                      "A parameterized collection accessor cannot be annotated with XmlElements that has a value with a length greater than one.");
    }

    if (element.isWrapped()) {
      XmlElementWrapper wrapper = element.getAnnotation(XmlElementWrapper.class);

      String namespace = wrapper.namespace();
      String typeNamespace = element.getTypeDefinition().getTargetNamespace();
      //use the empty string for comparison in the case of the empty namespace.
      if (namespace == null) {
        namespace = "";
      }
      if (typeNamespace == null) {
        typeNamespace = "";
      }

      if ((!"##default".equals(namespace)) && (!typeNamespace.equals(namespace))) {
        result.addError(element.getPosition(), "Enunciate doesn't support element wrappers of a different namespace than their containing type definition.  " +
          "The spec is unclear as to why this should be allowed because you could just use an @XmlElement annotation to accomplish the same thing with more clarity.");
      }
    }

    return result;
  }

  public ValidationResult validateValue(Value value) {
    ValidationResult result = validateAccessor(value);

    XmlTypeMirror baseType = value.getBaseType();
    if (baseType == null) {
      result.addError(value.getPosition(), "No base type specified.");
    }
    else if ((baseType instanceof XmlClassType) && (((XmlClassType) baseType).getTypeDefinition() instanceof ComplexTypeDefinition)) {
      result.addError(value.getPosition(), "An xml value must have a simple base type. " + new QName(baseType.getNamespace(), baseType.getName())
        + " is a complex type.");
    }

    return result;
  }

  public ValidationResult validateElementRef(ElementRef elementRef) {
    ValidationResult result = validateAccessor(elementRef);

    if (elementRef.getChoices().isEmpty()) {
      XmlTypeMirror baseType = elementRef.getBaseType();
      result.addError(elementRef.getPosition(), "No root elements found for " + new QName(baseType.getNamespace(), baseType.getName()).toString() + ".");
    }

    if ((elementRef.getAnnotation(XmlElement.class) != null) || (elementRef.getAnnotation(XmlElements.class) != null)) {
      result.addError(elementRef.getPosition(), "The xml element ref cannot be annotated also with XmlElement or XmlElements.");
    }

    if (elementRef.isWrapped()) {
      XmlElementWrapper wrapper = elementRef.getAnnotation(XmlElementWrapper.class);

      String namespace = wrapper.namespace();
      String typeNamespace = elementRef.getTypeDefinition().getTargetNamespace();
      //use the empty string for comparison in the case of the empty namespace.
      if (namespace == null) {
        namespace = "";
      }
      if (typeNamespace == null) {
        typeNamespace = "";
      }

      if ((!"##default".equals(namespace)) && (!typeNamespace.equals(namespace))) {
        result.addError(elementRef.getPosition(), "Enunciate doesn't support element wrappers of a different namespace than their containing type definition.  " +
          "The spec is unclear as to why this should be allowed because you could just use an @XmlElement annotation to accomplish the same thing with more clarity.");
      }
    }

    return result;
  }

  public ValidationResult validateAccessor(Accessor accessor) {
    ValidationResult result = new ValidationResult();

    if (accessor.getDelegate() instanceof PropertyDeclaration) {
      PropertyDeclaration property = (PropertyDeclaration) accessor.getDelegate();
      DecoratedMethodDeclaration getter = property.getGetter();
      DecoratedMethodDeclaration setter = property.getSetter();

      if ((getter != null) && (setter != null)) {
        //find all JAXB annotations that are on both the setter and the getter...
        Map<String, AnnotationMirror> getterAnnotations = getter.getAnnotations();
        Map<String, AnnotationMirror> setterAnnotations = setter.getAnnotations();
        for (String annotation : getterAnnotations.keySet()) {
          if ((annotation.startsWith(XmlElement.class.getPackage().getName())) && (setterAnnotations.containsKey(annotation))) {
            result.addError(setter.getPosition(), "'" + annotation + "' is duplicated between the getter and setter.");
          }
        }
      }
      else {
        result.addError(accessor.getPosition(), "A property accessor needs both a setter and a getter.");
      }
    }

    if (accessor.getAnnotation(XmlIDREF.class) != null) {
      XmlTypeMirror baseType = accessor.getBaseType();
      if ((!(baseType instanceof XmlClassType)) || (((XmlClassType) baseType).getTypeDefinition().getXmlID() == null)) {
        result.addError(accessor.getPosition(), "An XML IDREF must have a base type that references another type that has an XML ID.");
      }
    }

    return result;
  }

  public ValidationResult validateXmlID(Accessor accessor) {
    ValidationResult result = new ValidationResult();

    TypeMirror accessorType = accessor.getAccessorType();
    if (!(accessorType instanceof DeclaredType) || !((DeclaredType) accessorType).getDeclaration().getQualifiedName().startsWith(String.class.getName())) {
      result.addError(accessor.getPosition(), "An xml id must be a string.");
    }

    return result;
  }
}
