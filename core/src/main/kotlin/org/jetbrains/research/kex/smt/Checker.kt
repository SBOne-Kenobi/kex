package org.jetbrains.research.kex.smt

import org.jetbrains.research.kex.asm.state.PredicateStateAnalysis
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.predicate.PredicateType
import org.jetbrains.research.kex.state.transformer.ConstantPropagator
import org.jetbrains.research.kex.state.transformer.MemorySpacer
import org.jetbrains.research.kex.state.transformer.Optimizer
import org.jetbrains.research.kex.state.transformer.Simplifier
import org.jetbrains.research.kex.util.log
import org.jetbrains.research.kfg.ir.Method
import org.jetbrains.research.kfg.ir.value.instruction.Instruction

class Checker(val method: Method, val psa: PredicateStateAnalysis) {
    private fun prepareState(state: PredicateState): PredicateState {
        val optimized = Optimizer().transform(state)

        val propagated = ConstantPropagator().transform(optimized)
        val memspaced = MemorySpacer(propagated).transform(propagated)
        val simplified = Simplifier().transform(memspaced)
        return simplified
    }

    fun checkReachable(inst: Instruction): Result {
        log.debug("Checking reachability of ${inst.print()}")

        val state = psa.getInstructionState(inst)
        val path = state.filterByType(PredicateType.Path())
        val finalState = prepareState(state)
        val finalPath = prepareState(path)

        log.debug("State: $finalState")
        log.debug("Path: $finalPath")

        val result = SMTProxySolver().isPathPossible(finalState, finalPath)
        log.debug("Acquired result:")
        when (result) {
            is Result.SatResult -> log.debug("Reachable")
            is Result.UnsatResult -> log.debug("Unreachable")
            is Result.UnknownResult -> log.debug("Unknown")
        }
        return result
    }
}