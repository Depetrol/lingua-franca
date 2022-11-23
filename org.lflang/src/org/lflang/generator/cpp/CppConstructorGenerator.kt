/*************
 * Copyright (c) 2021, TU Dresden.

 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:

 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.

 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.

 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
 * THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ***************/

package org.lflang.generator.cpp

import org.lflang.generator.PrependOperator
import org.lflang.lf.Reactor

/** A code generator for the C++ constructor of a reactor class */
class CppConstructorGenerator(
    private val reactor: Reactor,
    private val parameters: CppParameterGenerator,
    private val state: CppStateGenerator,
    private val instances: CppInstanceGenerator,
    private val timers: CppTimerGenerator,
    private val actions: CppActionGenerator,
    private val ports: CppPortGenerator,
    private val reactions: CppReactionGenerator,
) {

    /**
     * Get a list of all parameters as they appear in the argument list of the constructors.
     *
     * @param withDefaults If true, then include default parameter values.
     * @return a list of Strings containing all parameters to be used in the constructor signature
     */
    private fun parameterArguments(withDefaults: Boolean) = with(CppParameterGenerator) {
        if (withDefaults) reactor.parameters.map { "${it.constRefType} ${it.name} = ${it.defaultValue}" }
        else reactor.parameters.map { "${it.constRefType} ${it.name}" }
    }

    private fun outerSignature(withDefaults: Boolean, fromEnvironment: Boolean): String {
        val parameterArgs = parameterArguments(withDefaults)
        val environmentOrContainer = if (fromEnvironment) "reactor::Environment* environment" else "reactor::Reactor* container"
        return if (parameterArgs.isEmpty())
            """${reactor.name}(const std::string& name, $environmentOrContainer)"""
        else with(PrependOperator) {
            """
                |${reactor.name}(
                |  const std::string& name,
                |  $environmentOrContainer,
            ${" |  "..parameterArgs.joinToString(",\n", postfix = ")")}
            """.trimMargin()
        }
    }

    private fun innerSignature(): String {
        val args = parameterArguments(false)
        return when (args.size) {
            0    -> "Inner(reactor::Reactor* reactor)"
            1    -> "Inner(reactor::Reactor* reactor, ${args[0]})"
            else -> with(PrependOperator) {
                """
                    |Inner(
                    |  reactor::Reactor* reactor,
                ${" |  "..args.joinToString(",\n")})
                """.trimMargin()
            }
        }
    }

    /** Get the constructor declaration of the outer reactor class */
    fun generateOuterDeclaration(fromEnvironment: Boolean) = "${outerSignature(true, fromEnvironment)};"

    /** Get the constructor definition of the outer reactor class */
    fun generateOuterDefinition(fromEnvironment: Boolean): String {
        val innerParameters = listOf("this") + reactor.parameters.map { it.name }
        return with(PrependOperator) {
            """
                |${reactor.templateLine}
                |${reactor.templateName}::${outerSignature(false, fromEnvironment)}
                |  : reactor::Reactor(name, ${if (fromEnvironment) "environment" else "container"})
            ${" |  "..instances.generateInitializers()}
            ${" |  "..timers.generateInitializers()}
            ${" |  "..actions.generateInitializers()}
            ${" |  "..reactions.generateReactionViewInitializers()}
                |  , __lf_inner(${innerParameters.joinToString(", ") })
                |{
            ${" |  "..ports.generateConstructorInitializers()}
            ${" |  "..instances.generateConstructorInitializers()}
            ${" |  "..reactions.generateReactionViewConstructorInitializers()}
                |}
            """.trimMargin()
        }
    }

    /** Get the constructor declaration of the inner reactor class */
    fun generateInnerDeclaration() = "${innerSignature()};"

    fun generateInnerDefinition(): String {
        return with(PrependOperator) {
            """
                |${reactor.templateLine}
                |${reactor.templateName}::Inner::${innerSignature()}
                |  : LFScope(reactor)
            ${" |  "..parameters.generateInitializers()}
            ${" |  "..state.generateInitializers()}
                |{}
            """.trimMargin()
        }
    }
}