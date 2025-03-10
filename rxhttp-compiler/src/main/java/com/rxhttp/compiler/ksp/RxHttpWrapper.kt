package com.rxhttp.compiler.ksp

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.rxhttp.compiler.common.toParamNames
import com.rxhttp.compiler.rxHttpPackage
import com.rxhttp.compiler.rxhttpKClass
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.jvm.jvmStatic
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import com.squareup.kotlinpoet.ksp.toTypeVariableName
import com.squareup.kotlinpoet.ksp.writeTo
import rxhttp.wrapper.annotation.Converter
import rxhttp.wrapper.annotation.Domain
import rxhttp.wrapper.annotation.OkClient
import rxhttp.wrapper.annotation.Param

/**
 * User: ljx
 * Date: 2020/5/30
 * Time: 19:03
 */
class RxHttpWrapper(private val logger: KSPLogger) {

    private val classMap = LinkedHashMap<String, Wrapper>()

    private val elementMap = LinkedHashMap<String, KSClassDeclaration>()
    private val ksFiles = mutableSetOf<KSFile>()

    @KspExperimental
    fun add(ksClassDeclaration: KSClassDeclaration) {
        val annotation =
            ksClassDeclaration.getAnnotationsByType(Param::class).firstOrNull() ?: return
        val name: String = annotation.methodName
        elementMap[name] = ksClassDeclaration
        ksFiles.add(ksClassDeclaration.containingFile!!)
    }

    @KspExperimental
    fun addOkClient(property: KSPropertyDeclaration) {
        val okClient = property.getAnnotationsByType(OkClient::class).firstOrNull() ?: return
        if (okClient.className.isEmpty()) return
        var wrapper = classMap[okClient.className]
        if (wrapper == null) {
            wrapper = Wrapper()
            classMap[okClient.className] = wrapper
        }
        if (wrapper.okClientName != null) {
            val msg = "@OkClient annotation className cannot be the same"
            logger.error(msg, property)
        }
        var name = okClient.name
        if (name.isBlank()) {
            name = property.simpleName.asString().firstLetterUpperCase()
        }
        wrapper.okClientName = name
        ksFiles.add(property.containingFile!!)
    }

    @KspExperimental
    fun addConverter(property: KSPropertyDeclaration) {
        val converter = property.getAnnotationsByType(Converter::class).firstOrNull() ?: return
        if (converter.className.isEmpty()) return
        var wrapper = classMap[converter.className]
        if (wrapper == null) {
            wrapper = Wrapper()
            classMap[converter.className] = wrapper
        }
        if (wrapper.converterName != null) {
            val msg = "@Converter annotation className cannot be the same"
            logger.error(msg, property)
        }
        var name = converter.name
        if (name.isBlank()) {
            name = property.simpleName.asString().firstLetterUpperCase()
        }
        wrapper.converterName = name
        ksFiles.add(property.containingFile!!)
    }

    @KspExperimental
    fun addDomain(property: KSPropertyDeclaration) {
        val domain = property.getAnnotationsByType(Domain::class).firstOrNull() ?: return
        if (domain.className.isEmpty()) return
        var wrapper = classMap[domain.className]
        if (wrapper == null) {
            wrapper = Wrapper()
            classMap[domain.className] = wrapper
        }
        if (wrapper.domainName != null) {
            val msg = "@Domain annotation className cannot be the same"
            logger.error(msg, property)
        }
        var name = domain.name
        if (name.isBlank()) {
            name = property.simpleName.asString().firstLetterUpperCase()
        }
        wrapper.domainName = name
        ksFiles.add(property.containingFile!!)
    }

    @KspExperimental
    fun generateRxWrapper(codeGenerator: CodeGenerator) {
        val requestFunList = generateRequestFunList()

        //生成多个RxHttp的包装类
        classMap.forEach { (className, wrapper) ->
            val funBody = CodeBlock.builder()
            wrapper.converterName?.let {
                funBody.addStatement("set$it()")
            }
            wrapper.okClientName?.let {
                funBody.addStatement("set$it()")
            }
            wrapper.domainName?.let {
                funBody.addStatement("setDomainTo${it}IfAbsent()")
            }
            val wildcard = TypeVariableName("*")
            val rxHttpName = rxhttpKClass.parameterizedBy(wildcard, wildcard)
            val typeVariable = TypeVariableName("R", rxHttpName)
            val funList = ArrayList<FunSpec>()
            FunSpec.builder("wrapper")
                .addKdoc("本类所有方法都会调用本方法")
                .addModifiers(KModifier.PRIVATE)
                .receiver(typeVariable)
                .addTypeVariable(typeVariable)
                .addCode(funBody.build())
                .addCode("return this")
                .returns(typeVariable)
                .build()
                .apply { funList.add(this) }
            funList.addAll(requestFunList)

            val rxHttpBuilder = TypeSpec.objectBuilder("Rx${className}Http")
                .addKdoc(
                    """
                    本类由@Converter、@Domain、@OkClient注解中的className字段生成  类命名方式: Rx + {className字段值} + Http
                    Github
                    https://github.com/liujingxing/rxhttp
                    https://github.com/liujingxing/rxlife
                    https://github.com/liujingxing/rxhttp/wiki/FAQ
                    https://github.com/liujingxing/rxhttp/wiki/更新日志
                """.trimIndent()
                )
                .addFunctions(funList)

            FileSpec.builder(rxHttpPackage, "Rx${className}Http")
                .addType(rxHttpBuilder.build())
                .build()
                .writeTo(codeGenerator, Dependencies(false, *ksFiles.toTypedArray()))
        }
    }


    @KspExperimental
    private fun generateRequestFunList(): ArrayList<FunSpec> {
        val funList = ArrayList<FunSpec>() //方法集合
        val funMap = LinkedHashMap<String, String>()
        funMap["get"] = "RxHttpNoBodyParam"
        funMap["head"] = "RxHttpNoBodyParam"
        funMap["postBody"] = "RxHttpBodyParam"
        funMap["putBody"] = "RxHttpBodyParam"
        funMap["patchBody"] = "RxHttpBodyParam"
        funMap["deleteBody"] = "RxHttpBodyParam"
        funMap["postForm"] = "RxHttpFormParam"
        funMap["putForm"] = "RxHttpFormParam"
        funMap["patchForm"] = "RxHttpFormParam"
        funMap["deleteForm"] = "RxHttpFormParam"
        funMap["postJson"] = "RxHttpJsonParam"
        funMap["putJson"] = "RxHttpJsonParam"
        funMap["patchJson"] = "RxHttpJsonParam"
        funMap["deleteJson"] = "RxHttpJsonParam"
        funMap["postJsonArray"] = "RxHttpJsonArrayParam"
        funMap["putJsonArray"] = "RxHttpJsonArrayParam"
        funMap["patchJsonArray"] = "RxHttpJsonArrayParam"
        funMap["deleteJsonArray"] = "RxHttpJsonArrayParam"
        funMap.forEach { (key, value) ->
            val returnType = rxhttpKClass.peerClass(value)
            FunSpec.builder(key)
                .jvmStatic()
                .addParameter("url", STRING)
                .addParameter("formatArgs", ANY, true, KModifier.VARARG)
                .addStatement("return RxHttp.${key}(url, *formatArgs).wrapper()")
                .returns(returnType)
                .build()
                .apply { funList.add(this) }
        }

        elementMap.forEach { (key, ksClass) ->
            val rxHttpTypeNames = ksClass.typeParameters.map {
                it.toTypeVariableName()
            }

            val rxHttpName = "RxHttp${ksClass.simpleName.asString()}"
            val rxHttpParamName = rxhttpKClass.peerClass(rxHttpName)
            val funReturnType = if (rxHttpTypeNames.isNotEmpty()) {
                rxHttpParamName.parameterizedBy(*rxHttpTypeNames.toTypedArray())
            } else {
                rxHttpParamName
            }

            val classTypeParams = ksClass.typeParameters.toTypeParameterResolver()
            //遍历public构造方法
            ksClass.getPublicConstructors().forEach { function ->
                val functionTypeParams = function.typeParameters
                    .toTypeParameterResolver(classTypeParams)
                //构造方法参数
                val parameterSpecs = function.parameters
                    .mapTo(ArrayList()) { it.toKParameterSpec(functionTypeParams) }
                if (parameterSpecs.firstOrNull()?.type == STRING) {
                    parameterSpecs.add(newParameterSpec("formatArgs", ANY, true, KModifier.VARARG))
                }
                val prefix = "return RxHttp.$key("
                val postfix = ").wrapper()"
                val funBody = parameterSpecs.toParamNames(prefix, postfix)
                FunSpec.builder(key)
                    .jvmStatic()
                    .addParameters(parameterSpecs)
                    .addTypeVariables(rxHttpTypeNames)
                    .addStatement(funBody)
                    .returns(funReturnType)
                    .build()
                    .apply { funList.add(this) }
            }
        }
        return funList
    }

    class Wrapper {
        var domainName: String? = null
        var converterName: String? = null
        var okClientName: String? = null
    }
}