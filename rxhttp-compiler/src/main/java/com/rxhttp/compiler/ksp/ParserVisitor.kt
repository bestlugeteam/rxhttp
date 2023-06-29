package com.rxhttp.compiler.ksp

import com.google.devtools.ksp.KSTypesNotPresentException
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.rxhttp.compiler.K_TYPE
import com.rxhttp.compiler.common.flapTypeParameterSpecTypes
import com.rxhttp.compiler.common.getTypeVariableString
import com.rxhttp.compiler.common.isArrayType
import com.rxhttp.compiler.common.toParamNames
import com.rxhttp.compiler.isDependenceRxJava
import com.rxhttp.compiler.rxHttpPackage
import com.rxhttp.compiler.rxhttpKClass
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.javapoet.JClassName
import com.squareup.kotlinpoet.javapoet.KotlinPoetJavaPoetPreview
import com.squareup.kotlinpoet.javapoet.toKClassName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import com.squareup.kotlinpoet.ksp.toTypeVariableName
import rxhttp.wrapper.annotation.Parser
import java.util.*

/**
 * User: ljx
 * Date: 2021/10/17
 * Time: 22:33
 */
class ParserVisitor(
    private val logger: KSPLogger
) : KSVisitorVoid() {

    private val ksClassMap = LinkedHashMap<String, KSClassDeclaration>()
    private val classNameMap = LinkedHashMap<String, List<ClassName>>()

    @OptIn(KotlinPoetJavaPoetPreview::class)
    @KspExperimental
    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        try {
            classDeclaration.checkParserValidClass()
            val annotation = classDeclaration.getAnnotationsByType(Parser::class).firstOrNull()
            var name = annotation?.name
            if (name.isNullOrBlank()) {
                name = classDeclaration.simpleName.toString()
            }
            ksClassMap[name] = classDeclaration
            val classNames =
                try {
                    annotation?.wrappers?.map { JClassName.get(it.java).toKClassName() }
                } catch (e: KSTypesNotPresentException) {
                    e.ksTypes.map {
                        ClassName.bestGuess(it.declaration.qualifiedName?.asString().toString())
                    }
                }
            classNames?.let { classNameMap[name] = it }

        } catch (e: NoSuchElementException) {
            logger.error(e, classDeclaration)
        }
    }

    @KspExperimental
    fun getFunList(codeGenerator: CodeGenerator, companionFunList: MutableList<FunSpec>): List<FunSpec> {
        val funList = ArrayList<FunSpec>()
        val rxHttpExtensions = RxHttpExtensions(logger)
        //遍历自定义解析器
        ksClassMap.forEach { (parserAlias, ksClass) ->
            //生成kotlin编写的toObservableXxx/toAwaitXxx/toFlowXxx方法
            rxHttpExtensions.generateRxHttpExtendFun(ksClass, parserAlias)
            //生成Java环境下toObservableXxx方法
            val toObservableXxxFunList = ksClass
                .getToObservableXxxFun(parserAlias, classNameMap, companionFunList)
            funList.addAll(toObservableXxxFunList)
        }
        rxHttpExtensions.generateClassFile(codeGenerator)
        return funList
    }
}

@KspExperimental
private fun KSClassDeclaration.getToObservableXxxFun(
    parserAlias: String,
    typeMap: LinkedHashMap<String, List<ClassName>>,
    companionFunList: MutableList<FunSpec>,
): List<FunSpec> {
    val funList = arrayListOf<FunSpec>()
    //onParser方法返回类型
    val onParserFunReturnType = findOnParserFunReturnType() ?: return emptyList()
    val typeVariableNames = typeParameters.map { it.toTypeVariableName() }
    val typeCount = typeVariableNames.size  //泛型数量
    val customParser = toClassName()

    //遍历构造方法
    for (constructor in getConstructors()) {
        if (!constructor.isValid(typeCount)) continue
        val classTypeParams = typeParameters.toTypeParameterResolver()
        val functionTypeParams =
            constructor.typeParameters.toTypeParameterResolver(classTypeParams)
        //原始参数
        val originParameterSpecs = constructor.parameters.map {
            it.toKParameterSpec(functionTypeParams)
        }
        //将原始参数里的第一个Type数组或Type类型可变参数转换为n个Type类型参数，n为泛型数量
        val typeParameterSpecs = originParameterSpecs.flapTypeParameterSpecTypes(typeVariableNames)
        //将Type类型参数转换为Class<T>类型参数，有泛型时才转
        val classParameterSpecs = typeParameterSpecs.typeToClassParameterSpecs(typeVariableNames)

        //方法名
        val funName = "toObservable$parserAlias"
        //返回类型(Observable<T>类型)
        val toObservableXxxFunReturnType = rxhttpKClass.peerClass("ObservableCall")
            .parameterizedBy(onParserFunReturnType)

        val wrapCustomParser = MemberName(rxHttpPackage, "wrap${customParser.simpleName}")
        val types = typeVariableNames.getTypeVariableString() // <T>, <K, V> 等

        //方法体
        val toObservableXxxFunBody =
            if (typeCount == 1 && onParserFunReturnType is TypeVariableName) {
                val paramNames = typeParameterSpecs.toParamNames()
                CodeBlock.of("return toObservable(%M$types($paramNames))", wrapCustomParser)
            } else {
                val paramNames = typeParameterSpecs.toParamNames(originParameterSpecs, typeCount)
                CodeBlock.of("return toObservable(%T$types($paramNames))", customParser)
            }

        if (isDependenceRxJava()) {
            FunSpec.builder(funName)
                .addTypeVariables(typeVariableNames)
                .addParameters(typeParameterSpecs)
                .addCode(toObservableXxxFunBody)
                .returns(toObservableXxxFunReturnType)
                .build()
                .apply { funList.add(this) }
        }

        if (typeCount == 1 && onParserFunReturnType is TypeVariableName) {
            val t = TypeVariableName("T")
            val typeUtil = ClassName("rxhttp.wrapper.utils", "TypeUtil")
            val okResponseParser = ClassName("rxhttp.wrapper.parse", "OkResponseParser")
            val parserClass = okResponseParser.peerClass("Parser").parameterizedBy(t)

            val suppressAnnotation = AnnotationSpec.builder(Suppress::class)
                .addMember("%S", "UNCHECKED_CAST")
                .build()

            val firstParamName = typeParameterSpecs.first().name
            val paramNames = typeParameterSpecs.toParamNames(originParameterSpecs, typeCount)
                .replace(firstParamName, "actualType")

            FunSpec.builder("wrap${customParser.simpleName}")
                .addAnnotation(suppressAnnotation)
                .addTypeVariable(t)
                .addParameters(typeParameterSpecs)
                .returns(parserClass)
                .addCode(
                    """
                    val actualType = %T.getActualType($firstParamName) ?: $firstParamName
                    val parser = %T<Any>($paramNames)
                    val actualParser = if (actualType == $firstParamName) parser else %T(parser)
                    return actualParser as Parser<T>
                """.trimIndent(), typeUtil, customParser, okResponseParser
                )
                .build()
                .apply { companionFunList.add(this) }
        }

        if (typeCount > 0 && isDependenceRxJava()) {
            val paramNames = classParameterSpecs.toParamNames(typeCount)

            //生成Class类型参数的toObservableXxx方法
            val funSpec = FunSpec.builder(funName)
                .addTypeVariables(typeVariableNames)
                .addParameters(classParameterSpecs)
                .addStatement("return $funName($paramNames)")  //方法里面的表达式
                .returns(toObservableXxxFunReturnType)
                .build()
                .apply { funList.add(this) }

            //过滤出非Any类型边界
            val nonAnyBounds = typeVariableNames.first().bounds.filter { typeName ->
                val name = typeName.toString()
                name != "kotlin.Any" && name != "kotlin.Any?"
            }

            //泛型数量等于1 且没有为泛型指定边界(Any类型边界除外)，才去生成Parser注解里wrappers字段对应的toObservableXxx方法
            if (typeCount == 1 && nonAnyBounds.isEmpty()) {
                val toObservableXxxFunList = funSpec
                    .getToObservableXxxWrapFun(parserAlias, onParserFunReturnType, typeMap)
                funList.addAll(toObservableXxxFunList)
            }
        }
    }
    return funList
}

/**
 * 生成Parser注解里wrappers字段指定类对应的toObservableXxx方法
 * @param parserAlias 解析器别名
 * @param onParserFunReturnType 解析器里onParser方法的返回类型
 * @param typeMap Parser注解里wrappers字段集合
 */
private fun FunSpec.getToObservableXxxWrapFun(
    parserAlias: String,
    onParserFunReturnType: TypeName,
    typeMap: LinkedHashMap<String, List<ClassName>>,
): List<FunSpec> {
    val funSpec = this  //解析器对应的toObservableXxx方法，没有经过wrappers字段包裹前的
    val funList = mutableListOf<FunSpec>()
    val parameterSpecs = funSpec.parameters
    val typeVariableNames = funSpec.typeVariables
    val typeCount = typeVariableNames.size

    val wrapperListClass = arrayListOf<ClassName>()
    typeMap[parserAlias]?.apply { wrapperListClass.addAll(this) }
    if (LIST !in wrapperListClass) {
        wrapperListClass.add(0, LIST)
    }
    wrapperListClass.forEach { wrapperClass ->

        //1、toObservableXxx方法返回值
        val onParserFunReturnWrapperType =
            if (onParserFunReturnType is ParameterizedTypeName) { // List<T>, Map<K,V>等包含泛型的类
                //返回类型有n个泛型，需要对每个泛型再次包装
                val typeNames = onParserFunReturnType.typeArguments.map { typeArg ->
                    wrapperClass.parameterizedBy(typeArg)
                }
                onParserFunReturnType.rawType.parameterizedBy(*typeNames.toTypedArray())
            } else {
                wrapperClass.parameterizedBy(onParserFunReturnType.copy(false))
            }
        val toFunReturnType = rxhttpKClass.peerClass("ObservableCall")
            .parameterizedBy(onParserFunReturnWrapperType.copy(onParserFunReturnType.isNullable))

        //2、toObservableXxx方法名
        val name = wrapperClass.toString()
        val simpleName = name.substring(name.lastIndexOf(".") + 1)
        val funName = "toObservable$parserAlias${simpleName}"

        //3、toObservableXxx方法体
        val funBody = CodeBlock.builder()
        val paramNames = StringBuilder()
        //遍历参数，取出参数名
        parameterSpecs.forEachIndexed { index, param ->
            if (index > 0) paramNames.append(", ")
            if (index < typeCount) {
                /*
                 * Class类型参数，需要进行再次包装，最后再取参数名
                 * 格式：val tTypeList = List::class.parameterizedBy(tType)
                 */
                val variableName = "${param.name}$simpleName"
                val expression =
                    "val $variableName = $simpleName::class.parameterizedBy(${param.name})"
                funBody.addStatement(expression)
                paramNames.append(variableName)
            } else {
                if (param.isVararg()) paramNames.append("*")
                paramNames.append(param.name)
            }
        }
        val returnStatement = "return ${funSpec.name}($paramNames)"
        funBody.addStatement(returnStatement)

        //4、生成toObservableXxx方法
        FunSpec.builder(funName)
            .addTypeVariables(typeVariableNames)
            .addParameters(funSpec.parameters)
            .addCode(funBody.build())  //方法里面的表达式
            .returns(toFunReturnType)
            .build()
            .apply { funList.add(this) }
    }
    return funList
}

private fun List<ParameterSpec>.typeToClassParameterSpecs(
    typeVariableNames: List<TypeVariableName>
): List<ParameterSpec> {
    val typeCount = typeVariableNames.size
    val className = Class::class.asClassName()
    return mapIndexed { index, parameterSpec ->
        if (index < typeCount) {
            val classType = className.parameterizedBy(typeVariableNames[index])
            parameterSpec.toBuilder(type = classType).build()
        } else parameterSpec
    }
}

private fun List<ParameterSpec>.toParamNames(
    originParamsSpecs: List<ParameterSpec>,
    typeCount: Int
): String {
    val isArrayType = typeCount > 0 && originParamsSpecs.first().isArrayType()
    val paramNames = StringBuilder()
    if (isArrayType) {
        paramNames.append("arrayOf(")
    }
    forEachIndexed { index, parameterSpec ->
        if (index > 0) paramNames.append(", ")
        if (parameterSpec.isVararg()) paramNames.append("*")
        paramNames.append(parameterSpec.name)
        if (isArrayType && index == typeCount - 1) {
            paramNames.append(")")
        }
    }
    return paramNames.toString()
}

private fun List<ParameterSpec>.toParamNames(typeCount: Int): String {
    val sb = StringBuilder()
    forEachIndexed { index, parameterSpec ->
        if (index > 0) sb.append(", ")
        if (parameterSpec.isVararg()) sb.append("*")
        sb.append(parameterSpec.name)
        if (index < typeCount && parameterSpec.type.isClassType()) {
            sb.append(" as Type")
        }
    }
    return sb.toString()
}

private fun TypeName.isClassType() = toString().startsWith("java.lang.Class")

@Throws(NoSuchElementException::class)
private fun KSClassDeclaration.checkParserValidClass() {
    val elementQualifiedName = qualifiedName?.asString()
    if (!isPublic()) {
        throw NoSuchElementException("The class '$elementQualifiedName' must be public")
    }
    if (isAbstract()) {
        val msg =
            "The class '$elementQualifiedName' is abstract. You can't annotate abstract classes with @${Parser::class.java.simpleName}"
        throw NoSuchElementException(msg)
    }

    val className = "rxhttp.wrapper.parse.Parser"
    if (!instanceOf(className)) {
        val msg =
            "The class '$elementQualifiedName' annotated with @${Parser::class.java.simpleName} must inherit from $className"
        throw NoSuchElementException(msg)
    }

    val typeParameterList = typeParameters
    val typeCount = typeParameterList.size
    if (typeCount > 0) {
        //查找带 java.lang.reflect.Type 参数的构造方法
        val isValid = getConstructors().any { it.isValid(typeCount) }
        if (!isValid) {
            val funBody = StringBuffer("public ${simpleName.asString()}(")
            for (i in typeParameterList.indices) {
                funBody.append(K_TYPE.toString())
                funBody.append(if (i == typeParameterList.lastIndex) ")" else ",")
            }
            val msg =
                "This class '$elementQualifiedName' must declare '$funBody' constructor fun"
            throw NoSuchElementException(msg)
        }
    }
}