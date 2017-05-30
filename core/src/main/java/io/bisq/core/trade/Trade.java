/*
 * This file is part of bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bisq.core.trade;

import com.google.common.base.Throwables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.bisq.common.app.DevEnv;
import io.bisq.common.app.Log;
import io.bisq.common.crypto.KeyRing;
import io.bisq.common.monetary.Price;
import io.bisq.common.monetary.Volume;
import io.bisq.common.proto.ProtoUtil;
import io.bisq.common.storage.Storage;
import io.bisq.common.taskrunner.Model;
import io.bisq.core.arbitration.Arbitrator;
import io.bisq.core.arbitration.Mediator;
import io.bisq.core.btc.wallet.BsqWalletService;
import io.bisq.core.btc.wallet.BtcWalletService;
import io.bisq.core.btc.wallet.TradeWalletService;
import io.bisq.core.filter.FilterManager;
import io.bisq.core.offer.Offer;
import io.bisq.core.offer.OpenOfferManager;
import io.bisq.core.proto.CoreProtoResolver;
import io.bisq.core.trade.protocol.ProcessModel;
import io.bisq.core.trade.protocol.TradeProtocol;
import io.bisq.core.user.User;
import io.bisq.generated.protobuffer.PB;
import io.bisq.network.p2p.DecryptedMessageWithPubKey;
import io.bisq.network.p2p.NodeAddress;
import io.bisq.network.p2p.P2PService;
import javafx.beans.property.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bouncycastle.util.encoders.Hex;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.Date;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Holds all data which are relevant to the trade, but not those which are only needed in the trade process as shared data between tasks. Those data are
 * stored in the task model.
 */
@Slf4j
public abstract class Trade implements Tradable, Model {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Enums
    ///////////////////////////////////////////////////////////////////////////////////////////

    public enum State {
        // #################### Phase PREPARATION
        // When trade protocol starts no funds are on stake
        PREPARATION(Phase.INIT),

        // At first part maker/taker have different roles
        // taker perspective
        // #################### Phase TAKER_FEE_PAID
        TAKER_PUBLISHED_TAKER_FEE_TX(Phase.TAKER_FEE_PUBLISHED),

        // PUBLISH_DEPOSIT_TX_REQUEST
        // maker perspective
        MAKER_SENT_PUBLISH_DEPOSIT_TX_REQUEST(Phase.TAKER_FEE_PUBLISHED),
        MAKER_SAW_ARRIVED_PUBLISH_DEPOSIT_TX_REQUEST(Phase.TAKER_FEE_PUBLISHED),
        MAKER_STORED_IN_MAILBOX_PUBLISH_DEPOSIT_TX_REQUEST(Phase.TAKER_FEE_PUBLISHED),
        MAKER_SEND_FAILED_PUBLISH_DEPOSIT_TX_REQUEST(Phase.TAKER_FEE_PUBLISHED),

        // taker perspective
        TAKER_RECEIVED_PUBLISH_DEPOSIT_TX_REQUEST(Phase.TAKER_FEE_PUBLISHED),


        // #################### Phase DEPOSIT_PAID
        TAKER_PUBLISHED_DEPOSIT_TX(Phase.DEPOSIT_PUBLISHED),


        // DEPOSIT_TX_PUBLISHED_MSG
        // taker perspective
        TAKER_SENT_DEPOSIT_TX_PUBLISHED_MSG(Phase.DEPOSIT_PUBLISHED),
        TAKER_SAW_ARRIVED_DEPOSIT_TX_PUBLISHED_MSG(Phase.DEPOSIT_PUBLISHED),
        TAKER_STORED_IN_MAILBOX_DEPOSIT_TX_PUBLISHED_MSG(Phase.DEPOSIT_PUBLISHED),
        TAKER_SEND_FAILED_DEPOSIT_TX_PUBLISHED_MSG(Phase.DEPOSIT_PUBLISHED),

        // maker perspective
        MAKER_RECEIVED_DEPOSIT_TX_PUBLISHED_MSG(Phase.DEPOSIT_PUBLISHED),

        // Alternatively the maker could have seen the deposit tx earlier before he received the DEPOSIT_TX_PUBLISHED_MSG
        MAKER_SAW_DEPOSIT_TX_IN_NETWORK(Phase.DEPOSIT_PUBLISHED),


        // #################### Phase DEPOSIT_CONFIRMED
        DEPOSIT_CONFIRMED_IN_BLOCK_CHAIN(Phase.DEPOSIT_CONFIRMED),


        // #################### Phase FIAT_SENT
        BUYER_CONFIRMED_IN_UI_FIAT_PAYMENT_INITIATED(Phase.FIAT_SENT),
        BUYER_SENT_FIAT_PAYMENT_INITIATED_MSG(Phase.FIAT_SENT),
        BUYER_SAW_ARRIVED_FIAT_PAYMENT_INITIATED_MSG(Phase.FIAT_SENT),
        BUYER_STORED_IN_MAILBOX_FIAT_PAYMENT_INITIATED_MSG(Phase.FIAT_SENT),
        BUYER_SEND_FAILED_FIAT_PAYMENT_INITIATED_MSG(Phase.FIAT_SENT),

        SELLER_RECEIVED_FIAT_PAYMENT_INITIATED_MSG(Phase.FIAT_SENT),

        // #################### Phase FIAT_RECEIVED
        SELLER_CONFIRMED_IN_UI_FIAT_PAYMENT_RECEIPT(Phase.FIAT_RECEIVED),

        // #################### Phase PAYOUT_PAID
        SELLER_PUBLISHED_PAYOUT_TX(Phase.PAYOUT_PUBLISHED),

        SELLER_SENT_PAYOUT_TX_PUBLISHED_MSG(Phase.PAYOUT_PUBLISHED),
        SELLER_SAW_ARRIVED_PAYOUT_TX_PUBLISHED_MSG(Phase.PAYOUT_PUBLISHED),
        SELLER_STORED_IN_MAILBOX_PAYOUT_TX_PUBLISHED_MSG(Phase.PAYOUT_PUBLISHED),
        SELLER_SEND_FAILED_PAYOUT_TX_PUBLISHED_MSG(Phase.PAYOUT_PUBLISHED),


        BUYER_RECEIVED_PAYOUT_TX_PUBLISHED_MSG(Phase.PAYOUT_PUBLISHED),
        // Alternatively the maker could have seen the payout tx earlier before he received the PAYOUT_TX_PUBLISHED_MSG
        BUYER_SAW_PAYOUT_TX_IN_NETWORK(Phase.PAYOUT_PUBLISHED),


        // #################### Phase WITHDRAWN
        WITHDRAW_COMPLETED(Phase.WITHDRAWN);

        @NotNull
        public Phase getPhase() {
            return phase;
        }

        @NotNull
        private final Phase phase;

        State(@NotNull Phase phase) {
            this.phase = phase;
        }

        public static Trade.State fromProto(PB.Trade.State state) {
            return ProtoUtil.enumFromProto(Trade.State.class, state.name());
        }

        public static PB.Trade.State toProtoMessage(Trade.State state) {
            return PB.Trade.State.valueOf(state.name());
        }
    }

    public enum Phase {
        INIT,
        TAKER_FEE_PUBLISHED,
        DEPOSIT_PUBLISHED,
        DEPOSIT_CONFIRMED,
        FIAT_SENT,
        FIAT_RECEIVED,
        PAYOUT_PUBLISHED,
        WITHDRAWN;

        public static Trade.Phase fromProto(PB.Trade.Phase phase) {
            return ProtoUtil.enumFromProto(Trade.Phase.class, phase.name());
        }

        public static PB.Trade.Phase toProtoMessage(Trade.Phase phase) {
            return PB.Trade.Phase.valueOf(phase.name());
        }
    }

    public enum DisputeState {
        NO_DISPUTE,
        DISPUTE_REQUESTED,
        DISPUTE_STARTED_BY_PEER,
        DISPUTE_CLOSED;

        public static Trade.DisputeState fromProto(PB.Trade.DisputeState disputeState) {
            return ProtoUtil.enumFromProto(Trade.DisputeState.class, disputeState.name());
        }

        public static PB.Trade.DisputeState toProtoMessage(Trade.DisputeState disputeState) {
            return PB.Trade.DisputeState.valueOf(disputeState.name());
        }
    }

    public enum TradePeriodState {
        FIRST_HALF,
        SECOND_HALF,
        TRADE_PERIOD_OVER;

        public static Trade.TradePeriodState fromProto(PB.Trade.TradePeriodState tradePeriodState) {
            return ProtoUtil.enumFromProto(Trade.TradePeriodState.class, tradePeriodState.name());
        }

        public static PB.Trade.TradePeriodState toProtoMessage(Trade.TradePeriodState tradePeriodState) {
            return PB.Trade.TradePeriodState.valueOf(tradePeriodState.name());
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Persistable
    // Immutable
    @Getter
    private final Offer offer;
    @Getter
    private final boolean isCurrencyForTakerFeeBtc;
    @Getter
    private final long txFeeAsLong;
    @Getter
    private final long takerFeeAsLong;
    @Setter
    private long takeOfferDate;
    @Getter @Setter
    private ProcessModel processModel;

    //  Mutable
    @Nullable @Getter @Setter
    private String takerFeeTxId;
    @Nullable @Getter @Setter
    private String depositTxId;
    @Nullable @Getter @Setter
    private String payoutTxId;
    @Getter @Setter
    private long tradeAmountAsLong;
    @Setter
    private long tradePrice;
    @Nullable @Getter
    private NodeAddress tradingPeerNodeAddress;
    @Getter
    private State state = State.PREPARATION;
    @Getter
    private DisputeState disputeState = DisputeState.NO_DISPUTE;
    @Getter
    private TradePeriodState tradePeriodState = TradePeriodState.FIRST_HALF;
    @Nullable @Getter @Setter
    private Contract contract;
    @Nullable @Getter @Setter
    private String contractAsJson;
    @Nullable @Getter @Setter
    private byte[] contractHash;
    @Nullable @Getter @Setter
    private String takerContractSignature;
    @Nullable @Getter @Setter
    private String makerContractSignature;
    @Nullable @Getter
    private NodeAddress arbitratorNodeAddress;
    @Nullable @Getter
    private NodeAddress mediatorNodeAddress;
    @Nullable @Setter
    private byte[] arbitratorBtcPubKey;
    @Nullable @Getter @Setter
    private String takerPaymentAccountId;
    @Nullable
    private String errorMessage;


    // Transient
    // Immutable
    @Getter
    transient final private Coin txFee;
    @Getter
    transient final private Coin takerFee;
    @Getter // to set in constructor so not final but set at init
    transient private Storage<? extends TradableList> storage;
    @Getter // to set in constructor so not final but set at init
    transient private BtcWalletService btcWalletService;

    transient final private ObjectProperty<State> stateProperty = new SimpleObjectProperty<>(state);
    transient final private ObjectProperty<Phase> statePhaseProperty = new SimpleObjectProperty<>(state.phase);
    transient final private ObjectProperty<DisputeState> disputeStateProperty = new SimpleObjectProperty<>(disputeState);
    transient final private ObjectProperty<TradePeriodState> tradePeriodStateProperty = new SimpleObjectProperty<>(tradePeriodState);
    transient final private StringProperty errorMessageProperty = new SimpleStringProperty(errorMessage);

    //  Mutable
    transient protected TradeProtocol tradeProtocol;
    @Nullable @Setter
    transient private Date maxTradePeriodDate, halfTradePeriodDate;
    @Nullable
    transient private Transaction payoutTx;
    @Nullable
    transient private Transaction depositTx;
    @Nullable
    transient private Coin tradeAmount;

    transient private ObjectProperty<Coin> tradeAmountProperty;
    transient private ObjectProperty<Volume> tradeVolumeProperty;
    transient private Set<DecryptedMessageWithPubKey> decryptedMessageWithPubKeySet = new HashSet<>();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, initialization
    ///////////////////////////////////////////////////////////////////////////////////////////

    // maker
    protected Trade(Offer offer,
                    Coin txFee,
                    Coin takerFee,
                    boolean isCurrencyForTakerFeeBtc,
                    Storage<? extends TradableList> storage,
                    BtcWalletService btcWalletService) {
        this.offer = offer;
        this.txFee = txFee;
        this.takerFee = takerFee;
        this.isCurrencyForTakerFeeBtc = isCurrencyForTakerFeeBtc;
        this.storage = storage;
        this.btcWalletService = btcWalletService;

        txFeeAsLong = txFee.value;
        takerFeeAsLong = takerFee.value;
        takeOfferDate = new Date().getTime();
        processModel = new ProcessModel();
    }


    // taker
    protected Trade(Offer offer,
                    Coin tradeAmount,
                    Coin txFee,
                    Coin takerFee,
                    boolean isCurrencyForTakerFeeBtc,
                    long tradePrice,
                    NodeAddress tradingPeerNodeAddress,
                    Storage<? extends TradableList> storage,
                    BtcWalletService btcWalletService) {

        this(offer, txFee, takerFee, isCurrencyForTakerFeeBtc, storage, btcWalletService);
        this.tradePrice = tradePrice;
        this.tradingPeerNodeAddress = tradingPeerNodeAddress;

        setTradeAmount(tradeAmount);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public Message toProtoMessage() {
        final PB.Trade.Builder builder = PB.Trade.newBuilder()
                .setOffer(offer.toProtoMessage())
                .setIsCurrencyForTakerFeeBtc(isCurrencyForTakerFeeBtc)
                .setTxFeeAsLong(txFeeAsLong)
                .setTakerFeeAsLong(takerFeeAsLong)
                .setTakeOfferDate(takeOfferDate)
                .setTradeAmountAsLong(tradeAmountAsLong)
                .setTradePrice(tradePrice)
                .setProcessModel(processModel.toProtoMessage())
                .setState(PB.Trade.State.valueOf(state.name()))
                .setDisputeState(PB.Trade.DisputeState.valueOf(disputeState.name()))
                .setTradePeriodState(PB.Trade.TradePeriodState.valueOf(tradePeriodState.name()));

        Optional.ofNullable(takerFeeTxId).ifPresent(builder::setTakerFeeTxId);
        Optional.ofNullable(depositTxId).ifPresent(builder::setDepositTxId);
        Optional.ofNullable(payoutTxId).ifPresent(builder::setPayoutTxId);
        Optional.ofNullable(tradingPeerNodeAddress).ifPresent(e -> builder.setTradingPeerNodeAddress(tradingPeerNodeAddress.toProtoMessage()));
        Optional.ofNullable(contract).ifPresent(e -> builder.setContract(contract.toProtoMessage()));
        Optional.ofNullable(contractAsJson).ifPresent(builder::setContractAsJson);
        Optional.ofNullable(contractHash).ifPresent(e -> builder.setContractHash(ByteString.copyFrom(contractHash)));
        Optional.ofNullable(takerContractSignature).ifPresent(builder::setTakerContractSignature);
        Optional.ofNullable(makerContractSignature).ifPresent(builder::setMakerContractSignature);
        Optional.ofNullable(arbitratorNodeAddress).ifPresent(e -> builder.setArbitratorNodeAddress(arbitratorNodeAddress.toProtoMessage()));
        Optional.ofNullable(mediatorNodeAddress).ifPresent(e -> builder.setMediatorNodeAddress(mediatorNodeAddress.toProtoMessage()));
        Optional.ofNullable(arbitratorBtcPubKey).ifPresent(e -> builder.setArbitratorBtcPubKey(ByteString.copyFrom(arbitratorBtcPubKey)));
        Optional.ofNullable(takerPaymentAccountId).ifPresent(builder::setTakerPaymentAccountId);
        Optional.ofNullable(errorMessage).ifPresent(builder::setErrorMessage);

        return builder.build();
    }

    public static Trade fromProto(Trade trade, PB.Trade proto, CoreProtoResolver coreProtoResolver) {
        trade.setTakeOfferDate(proto.getTakeOfferDate());
        trade.setProcessModel(ProcessModel.fromProto(proto.getProcessModel(), coreProtoResolver));
        trade.setState(State.fromProto(proto.getState()));
        trade.setDisputeState(DisputeState.fromProto(proto.getDisputeState()));
        trade.setTradePeriodState(TradePeriodState.fromProto(proto.getTradePeriodState()));

        trade.setTakerFeeTxId(proto.getTakerFeeTxId().isEmpty() ? null : proto.getTakerFeeTxId());
        trade.setDepositTxId(proto.getDepositTxId().isEmpty() ? null : proto.getDepositTxId());
        trade.setPayoutTxId(proto.getPayoutTxId().isEmpty() ? null : proto.getPayoutTxId());
        trade.setTradingPeerNodeAddress(NodeAddress.fromProto(proto.getTradingPeerNodeAddress()));
        trade.setContract(Contract.fromProto(proto.getContract(), coreProtoResolver));
        trade.setContractAsJson(proto.getContractAsJson().isEmpty() ? null : proto.getContractAsJson());
        trade.setTakerContractSignature(proto.getTakerContractSignature().isEmpty() ? null : proto.getTakerContractSignature());
        trade.setMakerContractSignature(proto.getMakerContractSignature().isEmpty() ? null : proto.getMakerContractSignature());
        trade.setArbitratorNodeAddress(NodeAddress.fromProto(proto.getArbitratorNodeAddress()));
        trade.setMediatorNodeAddress(NodeAddress.fromProto(proto.getMediatorNodeAddress()));
        trade.setArbitratorBtcPubKey(proto.getArbitratorBtcPubKey().toByteArray());
        trade.setTakerPaymentAccountId(proto.getTakerPaymentAccountId().isEmpty() ? null : proto.getTakerPaymentAccountId());
        trade.setErrorMessage(proto.getErrorMessage().isEmpty() ? null : proto.getErrorMessage());

        return trade;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void setTransientFields(Storage<? extends TradableList> storage, BtcWalletService btcWalletService) {
        this.storage = storage;
        this.btcWalletService = btcWalletService;
    }

    public void init(P2PService p2PService,
                     BtcWalletService btcWalletService,
                     BsqWalletService bsqWalletService,
                     TradeWalletService tradeWalletService,
                     TradeManager tradeManager,
                     OpenOfferManager openOfferManager,
                     User user,
                     FilterManager filterManager,
                     KeyRing keyRing,
                     boolean useSavingsWallet,
                     Coin fundsNeededForTrade) {
        Log.traceCall();
        processModel.onAllServicesInitialized(offer,
                tradeManager,
                openOfferManager,
                p2PService,
                btcWalletService,
                bsqWalletService,
                tradeWalletService,
                user,
                filterManager,
                keyRing,
                useSavingsWallet,
                fundsNeededForTrade);

        createTradeProtocol();

        // if we have already received a msg we apply it. 
        // removeDecryptedMsgWithPubKey will be called synchronous after apply. We don't have threaded context 
        // or async calls there.
        decryptedMessageWithPubKeySet.stream()
                .forEach(msg -> tradeProtocol.applyMailboxMessage(msg, this));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    // The deserialized tx has not actual confidence data, so we need to get the fresh one from the wallet.
    public void updateDepositTxFromWallet() {
        if (getDepositTx() != null)
            setDepositTx(processModel.getTradeWalletService().getWalletTx(getDepositTx().getHash()));
    }

    public void setDepositTx(Transaction tx) {
        log.debug("setDepositTx " + tx);
        this.depositTx = tx;
        depositTxId = depositTx.getHashAsString();
        setupConfidenceListener();
        persist();
    }

    @Nullable
    public Transaction getDepositTx() {
        if (depositTx == null)
            depositTx = depositTxId != null ? btcWalletService.getTransaction(Sha256Hash.wrap(depositTxId)) : null;
        return depositTx;
    }

    // We don't need to persist the msg as if we dont apply it it will not be removed from the P2P network and we 
    // will received it again at next startup. Such might happen in edge cases when the user shuts down after we 
    // received the msb but before the init is called.
    public void addDecryptedMessageWithPubKey(DecryptedMessageWithPubKey decryptedMessageWithPubKey) {
        log.trace("addDecryptedMessageWithPubKey decryptedMessageWithPubKey=" + decryptedMessageWithPubKey);
        if (!decryptedMessageWithPubKeySet.contains(decryptedMessageWithPubKey)) {
            decryptedMessageWithPubKeySet.add(decryptedMessageWithPubKey);

            // If we have already initialized we apply. 
            // removeDecryptedMsgWithPubKey will be called synchronous after apply. We don't have threaded context 
            // or async calls there.
            if (tradeProtocol != null)
                tradeProtocol.applyMailboxMessage(decryptedMessageWithPubKey, this);
        }
    }

    public void removeDecryptedMessageWithPubKey(DecryptedMessageWithPubKey decryptedMessageWithPubKey) {
        log.trace("removeDecryptedMessageWithPubKey decryptedMessageWithPubKey=" + decryptedMessageWithPubKey);
        if (decryptedMessageWithPubKeySet.contains(decryptedMessageWithPubKey))
            decryptedMessageWithPubKeySet.remove(decryptedMessageWithPubKey);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Model implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Get called from taskRunner after each completed task
    @Override
    public void persist() {
        if (storage != null)
            storage.queueUpForSave();
    }

    @Override
    public void onComplete() {
        persist();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Abstract 
    ///////////////////////////////////////////////////////////////////////////////////////////

    abstract protected void createTradeProtocol();

    abstract public Coin getPayoutAmount();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Setters 
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void setState(State state) {
        log.info("Trade state={}, id={}", state, getShortId());
        if (state.getPhase().ordinal() >= this.state.getPhase().ordinal()) {
            boolean changed = this.state != state;
            this.state = state;
            stateProperty.set(state);
            statePhaseProperty.set(state.getPhase());

            if (state == State.WITHDRAW_COMPLETED && tradeProtocol != null)
                tradeProtocol.completed();

            if (changed)
                persist();
        } else {
            final String message = "we got a state change to a previous phase. that is likely a bug.\n" +
                    "old state is: " + this.state + ". New state is: " + state;
            log.error(message);
            if (DevEnv.DEV_MODE)
                throw new RuntimeException(message);
        }
    }

    public void setDisputeState(DisputeState disputeState) {
        Log.traceCall("disputeState=" + disputeState + "\n\ttrade=" + this);
        boolean changed = this.disputeState != disputeState;
        this.disputeState = disputeState;
        disputeStateProperty.set(disputeState);
        if (changed)
            persist();
    }


    public void setTradePeriodState(TradePeriodState tradePeriodState) {
        boolean changed = this.tradePeriodState != tradePeriodState;
        this.tradePeriodState = tradePeriodState;
        tradePeriodStateProperty.set(tradePeriodState);
        if (changed)
            persist();
    }

    public void setTradingPeerNodeAddress(NodeAddress tradingPeerNodeAddress) {
        if (tradingPeerNodeAddress == null)
            log.error("tradingPeerAddress=null");
        else
            this.tradingPeerNodeAddress = tradingPeerNodeAddress;
    }

    public void setTradeAmount(Coin tradeAmount) {
        this.tradeAmount = tradeAmount;
        tradeAmountAsLong = tradeAmount.value;
        getTradeAmountProperty().set(tradeAmount);
        getTradeVolumeProperty().set(getTradeVolume());
    }

    public void setPayoutTx(Transaction payoutTx) {
        this.payoutTx = payoutTx;
        payoutTxId = payoutTx.getHashAsString();
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        errorMessageProperty.set(errorMessage);
    }

    public void setArbitratorNodeAddress(NodeAddress arbitratorNodeAddress) {
        this.arbitratorNodeAddress = arbitratorNodeAddress;
        if (processModel.getUser() != null) {
            Arbitrator arbitrator = processModel.getUser().getAcceptedArbitratorByAddress(arbitratorNodeAddress);
            checkNotNull(arbitrator, "arbitrator must not be null");
            arbitratorBtcPubKey = arbitrator.getBtcPubKey();
        }
    }

    public void setMediatorNodeAddress(NodeAddress mediatorNodeAddress) {
        this.mediatorNodeAddress = mediatorNodeAddress;
        if (processModel.getUser() != null) {
            Mediator mediator = processModel.getUser().getAcceptedMediatorByAddress(mediatorNodeAddress);
            checkNotNull(mediator, "mediator must not be null");
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getter
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Date getTakeOfferDate() {
        return new Date(takeOfferDate);
    }

    @Nullable
    public Volume getTradeVolume() {
        if (getTradeAmount() != null && getTradePrice() != null)
            return getTradePrice().getVolumeByAmount(getTradeAmount());
        else
            return null;
    }

    @Nullable
    public Date getMaxTradePeriodDate() {
        if (maxTradePeriodDate == null && getTakeOfferDate() != null)
            maxTradePeriodDate = new Date(getTakeOfferDate().getTime() + getOffer().getPaymentMethod().getMaxTradePeriod());

        return maxTradePeriodDate;
    }

    @Nullable
    public Date getHalfTradePeriodDate() {
        if (halfTradePeriodDate == null && getTakeOfferDate() != null)
            halfTradePeriodDate = new Date(getTakeOfferDate().getTime() + getOffer().getPaymentMethod().getMaxTradePeriod() / 2);

        return halfTradePeriodDate;
    }

    public boolean hasFailed() {
        return errorMessageProperty().get() != null;
    }

    public boolean isInPreparation() {
        return getState().getPhase().ordinal() == Phase.INIT.ordinal();
    }

    public boolean isTakerFeePublished() {
        return getState().getPhase().ordinal() >= Phase.TAKER_FEE_PUBLISHED.ordinal();
    }

    public boolean isDepositPublished() {
        return getState().getPhase().ordinal() >= Phase.DEPOSIT_PUBLISHED.ordinal();
    }

    public boolean isDepositConfirmed() {
        return getState().getPhase().ordinal() >= Phase.DEPOSIT_CONFIRMED.ordinal();
    }

    public boolean isFiatSent() {
        return getState().getPhase().ordinal() >= Phase.FIAT_SENT.ordinal();
    }

    public boolean isFiatReceived() {
        return getState().getPhase().ordinal() >= Phase.FIAT_RECEIVED.ordinal();
    }

    public boolean isPayoutPublished() {
        return getState().getPhase().ordinal() >= Phase.PAYOUT_PUBLISHED.ordinal() || isWithdrawn();
    }

    public boolean isWithdrawn() {
        return getState().getPhase().ordinal() == Phase.WITHDRAWN.ordinal();
    }

    public ReadOnlyObjectProperty<State> stateProperty() {
        return stateProperty;
    }

    public ReadOnlyObjectProperty<Phase> statePhaseProperty() {
        return statePhaseProperty;
    }

    public ReadOnlyObjectProperty<DisputeState> disputeStateProperty() {
        return disputeStateProperty;
    }

    public ReadOnlyObjectProperty<TradePeriodState> tradePeriodStateProperty() {
        return tradePeriodStateProperty;
    }

    public ReadOnlyObjectProperty<Coin> tradeAmountProperty() {
        return tradeAmountProperty;
    }

    public ReadOnlyObjectProperty<Volume> tradeVolumeProperty() {
        return tradeVolumeProperty;
    }

    public ReadOnlyStringProperty errorMessageProperty() {
        return errorMessageProperty;
    }

    @Override
    public Date getDate() {
        return getTakeOfferDate();
    }

    @Override
    public String getId() {
        return offer.getId();
    }

    @Override
    public String getShortId() {
        return offer.getShortId();
    }

    public Price getTradePrice() {
        return Price.valueOf(offer.getCurrencyCode(), tradePrice);
    }

    @Nullable
    public Coin getTradeAmount() {
        if (tradeAmount == null)
            tradeAmount = Coin.valueOf(tradeAmountAsLong);
        return tradeAmount;
    }

    @Nullable
    public Transaction getPayoutTx() {
        if (payoutTx == null)
            payoutTx = payoutTxId != null ? btcWalletService.getTransaction(Sha256Hash.wrap(payoutTxId)) : null;
        return payoutTx;
    }

    public String getErrorMessage() {
        return errorMessageProperty.get();
    }

    public byte[] getArbitratorBtcPubKey() {
        Arbitrator arbitrator = processModel.getUser().getAcceptedArbitratorByAddress(arbitratorNodeAddress);
        checkNotNull(arbitrator, "arbitrator must not be null");
        arbitratorBtcPubKey = arbitrator.getBtcPubKey();

        checkNotNull(arbitratorBtcPubKey, "ArbitratorPubKey must not be null");
        return arbitratorBtcPubKey;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    // lazy initialization
    private ObjectProperty<Coin> getTradeAmountProperty() {
        if (tradeAmountProperty == null)
            tradeAmountProperty = getTradeAmount() != null ? new SimpleObjectProperty<>(getTradeAmount()) : new SimpleObjectProperty<>();

        return tradeAmountProperty;
    }

    // lazy initialization
    private ObjectProperty<Volume> getTradeVolumeProperty() {
        if (tradeVolumeProperty == null)
            tradeVolumeProperty = getTradeVolume() != null ? new SimpleObjectProperty<>(getTradeVolume()) : new SimpleObjectProperty<>();
        return tradeVolumeProperty;
    }

    private void setupConfidenceListener() {
        if (getDepositTx() != null) {
            TransactionConfidence transactionConfidence = getDepositTx().getConfidence();
            if (transactionConfidence.getConfidenceType() == TransactionConfidence.ConfidenceType.BUILDING) {
                setConfirmedState();
            } else {
                ListenableFuture<TransactionConfidence> future = transactionConfidence.getDepthFuture(1);
                Futures.addCallback(future, new FutureCallback<TransactionConfidence>() {
                    @Override
                    public void onSuccess(TransactionConfidence result) {
                        setConfirmedState();
                    }

                    @Override
                    public void onFailure(@NotNull Throwable t) {
                        t.printStackTrace();
                        log.error(t.getMessage());
                        Throwables.propagate(t);
                    }
                });
            }
        } else {
            log.error("depositTx == null. That must not happen.");
        }
    }

    private void setConfirmedState() {
        // we only apply the state if we are not already further in the process
        if (!isDepositConfirmed())
            setState(State.DEPOSIT_CONFIRMED_IN_BLOCK_CHAIN);
    }

    @Override public String toString() {
        return "Trade{" +
                "\n     offer=" + offer +
                ",\n     isCurrencyForTakerFeeBtc=" + isCurrencyForTakerFeeBtc +
                ",\n     txFeeAsLong=" + txFeeAsLong +
                ",\n     takerFeeAsLong=" + takerFeeAsLong +
                ",\n     takeOfferDate=" + getTakeOfferDate() +
                ",\n     processModel=" + processModel +
                ",\n     takerFeeTxId='" + takerFeeTxId + '\'' +
                ",\n     depositTxId='" + depositTxId + '\'' +
                ",\n     payoutTxId='" + payoutTxId + '\'' +
                ",\n     tradeAmountAsLong=" + tradeAmountAsLong +
                ",\n     tradePrice=" + tradePrice +
                ",\n     tradingPeerNodeAddress=" + tradingPeerNodeAddress +
                ",\n     state=" + state +
                ",\n     disputeState=" + disputeState +
                ",\n     tradePeriodState=" + tradePeriodState +
                ",\n     contract=" + contract +
                ",\n     contractAsJson='" + contractAsJson + '\'' +
                ",\n     contractHash=" + (contractHash != null ? Hex.toHexString(contractHash) : "null") +
                ",\n     takerContractSignature='" + takerContractSignature + '\'' +
                ",\n     makerContractSignature='" + makerContractSignature + '\'' +
                ",\n     arbitratorNodeAddress=" + arbitratorNodeAddress +
                ",\n     mediatorNodeAddress=" + mediatorNodeAddress +
                ",\n     arbitratorBtcPubKey=" + (arbitratorBtcPubKey != null ? Hex.toHexString(arbitratorBtcPubKey) : "null") +
                ",\n     takerPaymentAccountId='" + takerPaymentAccountId + '\'' +
                ",\n     errorMessage='" + errorMessage + '\'' +
                ",\n     txFee=" + txFee +
                ",\n     takerFee=" + takerFee +
                ",\n     storage=" + storage +
                ",\n     btcWalletService=" + btcWalletService +
                ",\n     stateProperty=" + stateProperty +
                ",\n     statePhaseProperty=" + statePhaseProperty +
                ",\n     disputeStateProperty=" + disputeStateProperty +
                ",\n     tradePeriodStateProperty=" + tradePeriodStateProperty +
                ",\n     errorMessageProperty=" + errorMessageProperty +
                ",\n     tradeProtocol=" + tradeProtocol +
                ",\n     maxTradePeriodDate=" + maxTradePeriodDate +
                ",\n     halfTradePeriodDate=" + halfTradePeriodDate +
                ",\n     payoutTx=" + payoutTx +
                ",\n     depositTx=" + depositTx +
                ",\n     tradeAmount=" + tradeAmount +
                ",\n     tradeAmountProperty=" + tradeAmountProperty +
                ",\n     tradeVolumeProperty=" + tradeVolumeProperty +
                ",\n     decryptedMessageWithPubKeySet=" + decryptedMessageWithPubKeySet +
                "\n}";
    }

   /* @Override
    public String toString() {
        return "Trade{" +
                "\n\ttradeAmount=" + getTradeAmount() +
                "\n\ttradingPeerNodeAddress=" + tradingPeerNodeAddress +
                "\n\ttradeVolume=" + getTradeVolumeProperty().get() +
                "\n\toffer=" + offer +
                "\n\tprocessModel=" + processModel +
                "\n\tdecryptedMsgWithPubKeySet=" + decryptedMessageWithPubKeySet +
                "\n\ttakeOfferDate=" + getTakeOfferDate() +
                "\n\tstate=" + getState() +
                "\n\tdisputeState=" + getDisputeState() +
                "\n\ttradePeriodState=" + getTradePeriodState() +
                "\n\tdepositTx=" + getDepositTx() +
                "\n\ttakeOfferFeeTxId=" + takerFeeTxId +
                "\n\tcontract=" + contract +
                "\n\ttakerContractSignature.hashCode()='" + (takerContractSignature != null ?
                takerContractSignature.hashCode() : "") + '\'' +
                "\n\tmakerContractSignature.hashCode()='" + (makerContractSignature != null ?
                makerContractSignature.hashCode() : "") + '\'' +
                "\n\tpayoutTx=" + getPayoutTx() +
                "\n\tarbitratorNodeAddress=" + arbitratorNodeAddress +
                "\n\tmediatorNodeAddress=" + mediatorNodeAddress +
                "\n\ttakerPaymentAccountId='" + takerPaymentAccountId + '\'' +
                "\n\ttxFee='" + getTxFee().toFriendlyString() + '\'' +
                "\n\ttakeOfferFee='" + getTakerFee().toFriendlyString() + '\'' +
                "\n\terrorMessage='" + errorMessage + '\'' +
                '}';
    }*/
}