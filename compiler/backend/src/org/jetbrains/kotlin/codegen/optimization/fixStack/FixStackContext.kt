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

package org.jetbrains.kotlin.codegen.optimization.fixStack

import com.intellij.util.SmartList
import com.intellij.util.containers.SmartHashSet
import com.intellij.util.containers.Stack
import org.jetbrains.kotlin.codegen.inline.InlineCodegenUtil
import org.jetbrains.kotlin.codegen.optimization.common.InsnSequence
import org.jetbrains.kotlin.codegen.optimization.fixStack.forEachPseudoInsn
import org.jetbrains.kotlin.codegen.pseudoInsns.PseudoInsn
import org.jetbrains.kotlin.codegen.pseudoInsns.parsePseudoInsnOrNull
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.tree.*
import java.util.*
import kotlin.properties.Delegates

internal class FixStackContext(val methodNode: MethodNode) {
    val breakContinueGotoNodes = linkedSetOf<JumpInsnNode>()
    val fakeAlwaysTrueIfeqMarkers = arrayListOf<AbstractInsnNode>()
    val fakeAlwaysFalseIfeqMarkers = arrayListOf<AbstractInsnNode>()

    val saveStackNodesForTryStartLabel = hashMapOf<LabelNode, AbstractInsnNode>()
    val saveStackMarkerForRestoreMarker = hashMapOf<AbstractInsnNode, AbstractInsnNode>()
    val restoreStackMarkersForSaveMarker = hashMapOf<AbstractInsnNode, MutableList<AbstractInsnNode>>()

    val openingInlineMethodMarker = hashMapOf<AbstractInsnNode, AbstractInsnNode>()
    var consistentInlineMarkers: Boolean = true; private set

    init {
        val inlineMarkersStack = Stack<AbstractInsnNode>()

        InsnSequence(methodNode.instructions).forEach { insnNode ->
            val pseudoInsn = parsePseudoInsnOrNull(insnNode)
            when {
                pseudoInsn == PseudoInsn.FIX_STACK_BEFORE_JUMP ->
                    visitFixStackBeforeJump(insnNode, pseudoInsn)
                pseudoInsn == PseudoInsn.FAKE_ALWAYS_TRUE_IFEQ ->
                    visitFakeAlwaysTrueIfeq(insnNode, pseudoInsn)
                pseudoInsn == PseudoInsn.FAKE_ALWAYS_FALSE_IFEQ ->
                    visitFakeAlwaysFalseIfeq(insnNode, pseudoInsn)
                pseudoInsn == PseudoInsn.SAVE_STACK_BEFORE_TRY ->
                    visitSaveStackBeforeTry(insnNode, pseudoInsn)
                pseudoInsn == PseudoInsn.RESTORE_STACK_IN_TRY_CATCH ->
                    visitRestoreStackInTryCatch(insnNode, pseudoInsn)
                InlineCodegenUtil.isBeforeInlineMarker(insnNode) -> {
                    inlineMarkersStack.push(insnNode)
                }
                InlineCodegenUtil.isAfterInlineMarker(insnNode) -> {
                    assert(inlineMarkersStack.isNotEmpty(), "Mismatching after inline method marker at ${indexOf(insnNode)}")
                    openingInlineMethodMarker[insnNode] = inlineMarkersStack.pop()
                }
            }
        }

        if (inlineMarkersStack.isNotEmpty()) {
            consistentInlineMarkers = false
        }
    }

    private fun visitFixStackBeforeJump(insnNode: AbstractInsnNode, pseudoInsn: PseudoInsn) {
        val next = insnNode.getNext()
        assert(next.getOpcode() == Opcodes.GOTO, "$pseudoInsn should be followed by GOTO")
        breakContinueGotoNodes.add(next as JumpInsnNode)
    }

    private fun visitFakeAlwaysTrueIfeq(insnNode: AbstractInsnNode, pseudoInsn: PseudoInsn) {
        assert(insnNode.getNext().getOpcode() == Opcodes.IFEQ, "$pseudoInsn should be followed by IFEQ")
        fakeAlwaysTrueIfeqMarkers.add(insnNode)
    }

    private fun visitFakeAlwaysFalseIfeq(insnNode: AbstractInsnNode, pseudoInsn: PseudoInsn) {
        assert(insnNode.getNext().getOpcode() == Opcodes.IFEQ, "$pseudoInsn should be followed by IFEQ")
        fakeAlwaysFalseIfeqMarkers.add(insnNode)
    }

    private fun visitSaveStackBeforeTry(insnNode: AbstractInsnNode, pseudoInsn: PseudoInsn) {
        val tryStartLabel = insnNode.getNext()
        assert(tryStartLabel is LabelNode, "$pseudoInsn should be followed by a label")
        saveStackNodesForTryStartLabel[tryStartLabel as LabelNode] = insnNode
    }

    private fun visitRestoreStackInTryCatch(insnNode: AbstractInsnNode, pseudoInsn: PseudoInsn) {
        val restoreLabel = insnNode.getPrevious()?.getPrevious()
        if (restoreLabel !is LabelNode) {
            throw AssertionError("$pseudoInsn should be preceded by a catch block label")
        }
        val saveNode = findMatchingSaveNode(insnNode, pseudoInsn, restoreLabel)
        restoreStackMarkersForSaveMarker.getOrPut(saveNode, { SmartList<AbstractInsnNode>() }).add(insnNode)
        saveStackMarkerForRestoreMarker[insnNode] = saveNode
    }

    private fun findMatchingSaveNode(insnNode: AbstractInsnNode, pseudoInsn: PseudoInsn, restoreLabel: LabelNode): AbstractInsnNode? {
        val saveNodes = SmartHashSet<AbstractInsnNode>()
        methodNode.tryCatchBlocks.forEach { tcb ->
            if (restoreLabel == tcb.start || restoreLabel == tcb.handler) {
                saveStackNodesForTryStartLabel[tcb.start]?.let { saveNodes.add(it) }
            }
        }
        if (saveNodes.isEmpty()) {
            throw AssertionError("$pseudoInsn at ${indexOf(insnNode)}, in handler ${indexOf(restoreLabel)} is not matched")
        }
        else if (saveNodes.size() > 1) {
            throw AssertionError("$pseudoInsn at ${indexOf(insnNode)}, in handler ${indexOf(restoreLabel)} is matched by more than one save")
        }
        return saveNodes.first()
    }

    private fun indexOf(node: AbstractInsnNode) = methodNode.instructions.indexOf(node)

    fun hasAnyMarkers(): Boolean =
            breakContinueGotoNodes.isNotEmpty() ||
            fakeAlwaysTrueIfeqMarkers.isNotEmpty() ||
            fakeAlwaysFalseIfeqMarkers.isNotEmpty() ||
            saveStackNodesForTryStartLabel.isNotEmpty() ||
            openingInlineMethodMarker.isNotEmpty()

    fun isAnalysisRequired(): Boolean =
            breakContinueGotoNodes.isNotEmpty() ||
            saveStackNodesForTryStartLabel.isNotEmpty() ||
            openingInlineMethodMarker.isNotEmpty()

}