/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.debugger.stepping

import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.BreakpointStepMethodFilter
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.util.Range
import com.sun.jdi.Location
import org.jetbrains.kotlin.psi.JetFunctionLiteralExpression
import org.jetbrains.kotlin.types.expressions.OperatorConventions

public class KotlinLambdaMethodFilter(
        private val lambda: JetFunctionLiteralExpression,
        private val myCallingExpressionLines: Range<Int>?,
        private val isInline: Boolean
): BreakpointStepMethodFilter {
    private val myFirstStatementPosition: SourcePosition?
    private val myLastStatementLine: Int

    init {
        var firstStatementPosition: SourcePosition? = null
        var lastStatementPosition: SourcePosition? = null
        val body = lambda.getBodyExpression()!!
        val statements = body.getStatements()
        if (statements.isNotEmpty()) {
            firstStatementPosition = SourcePosition.createFromElement(statements.first())
            if (firstStatementPosition != null) {
                val lastStatement = statements.last()
                lastStatementPosition = SourcePosition.createFromOffset(firstStatementPosition.getFile(), lastStatement.getTextRange().getEndOffset())
            }
        }
        myFirstStatementPosition = firstStatementPosition
        myLastStatementLine = if (lastStatementPosition != null) lastStatementPosition.getLine() else -1
    }

    override fun getBreakpointPosition(): SourcePosition? {
        return myFirstStatementPosition
    }

    override fun getLastStatementLine(): Int {
        return myLastStatementLine
    }

    override fun locationMatches(process: DebugProcessImpl, location: Location): Boolean {
        val method = location.method()

        if (isInline) {
            return true
        }

        return isLambdaName(method.name())
    }

    override fun getCallingExpressionLines() = myCallingExpressionLines

    companion object {
        public fun isLambdaName(name: String?): Boolean {
            return name == OperatorConventions.INVOKE.asString()
        }
    }
}