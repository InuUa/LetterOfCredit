package eloc.flow

import co.paralleluniverse.fibers.Suspendable
import eloc.contract.LOC
import eloc.contract.LOCApplication
import eloc.state.LOCApplicationState
import eloc.state.LOCProperties
import eloc.state.LOCState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.flows.*
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.seconds
import java.time.LocalDate

object LOCApprovalFlow {
    @InitiatingFlow
    @StartableByRPC
    class Approve(val reference: StateRef) : FlowLogic<SignedTransaction>() {
        companion object {
            object GENERATING_APPROVAL_TRANSACTION : ProgressTracker.Step("Generating LOC transaction.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction.")
            object FINALIZING : ProgressTracker.Step("Recording and distributing transaction.")

            fun tracker() = ProgressTracker(
                    GENERATING_APPROVAL_TRANSACTION,
                    SIGNING_TRANSACTION,
                    FINALIZING
            )
        }

        override val progressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            val applicationTxState = serviceHub.loadState(reference)
            val application = applicationTxState.data as LOCApplicationState

            // Step 1. Generate transaction
            progressTracker.currentStep = GENERATING_APPROVAL_TRANSACTION
            val applicationProps = application.props
            val LOCProps = LOCProperties(applicationProps, LocalDate.now())

            val notary = serviceHub.networkMapCache.notaryIdentities.first()
            val loc = LOCState(beneficiaryPaid = false, advisoryPaid = false, issuerPaid = false, issued = true, terminated = false, shipped = false, props = LOCProps)

            val builder = TransactionBuilder(notary = notary)
            val appStateAndRef = StateAndRef(state = applicationTxState, ref = reference)
            builder.addInputState(appStateAndRef)
            builder.addOutputState(application.copy(status = LOCApplication.Status.APPROVED), LOCApplication.LOC_APPLICATION_CONTRACT_ID)
            builder.addOutputState(loc, LOC.LOC_CONTRACT_ID)
            builder.addCommand(LOCApplication.Commands.Approve(), application.issuer.owningKey)
            builder.addCommand(LOC.Commands.Issuance(), application.issuer.owningKey)

            // Step 2. Add timestamp
            progressTracker.currentStep = SIGNING_TRANSACTION
            val currentTime = serviceHub.clock.instant()
            builder.setTimeWindow(currentTime, 30.seconds)

            // Step 3. Verify transaction
            builder.verify(serviceHub)

            // Step 4. Sign transaction
            progressTracker.currentStep = FINALIZING
            val stx = serviceHub.signInitialTransaction(builder)

            // Step 5. Assuming no exceptions, we can now finalise the transaction.
            return subFlow(FinalityFlow(stx))
        }
    }

    @InitiatingFlow
    @InitiatedBy(Approve::class)
    class ReceiveApproval(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        companion object {
            object RECEIVING : ProgressTracker.Step("Receiving application")
            object VALIDATING : ProgressTracker.Step("Validating application")
            object SIGNING : ProgressTracker.Step("Signing application")
            object SUCCESS : ProgressTracker.Step("Application successfully recorded")

            fun tracker() = ProgressTracker(
                    RECEIVING,
                    VALIDATING,
                    SIGNING,
                    SUCCESS
            )
        }

        override val progressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            val flow = object : SignTransactionFlow(counterpartySession) {
                @Suspendable
                override fun checkTransaction(stx: SignedTransaction) {

                }
            }

            val stx = subFlow(flow)
            return waitForLedgerCommit(stx.id)
        }
    }
}

@CordaSerializable
sealed class LOCApprovalResult {
    class Success(val message: String?) : LOCApprovalResult() {
        override fun toString(): String = "Success($message)"
    }

    class Failure(val message: String?) : LOCApprovalResult() {
        override fun toString(): String = "Failure($message)"
    }
}