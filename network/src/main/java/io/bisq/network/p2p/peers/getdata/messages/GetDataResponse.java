package io.bisq.network.p2p.peers.getdata.messages;

import io.bisq.common.app.Capabilities;
import io.bisq.common.app.Version;
import io.bisq.common.network.Msg;
import io.bisq.generated.protobuffer.PB;
import io.bisq.network.p2p.SupportedCapabilitiesMsg;
import io.bisq.network.p2p.storage.payload.ProtectedMailboxStorageEntry;
import io.bisq.network.p2p.storage.payload.ProtectedStorageEntry;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.stream.Collectors;

public final class GetDataResponse implements SupportedCapabilitiesMsg {
    // That object is sent over the wire, so we need to take care of version compatibility.
    private static final long serialVersionUID = Version.P2P_NETWORK_VERSION;
    private final int messageVersion = Version.getP2PMessageVersion();

    public final HashSet<ProtectedStorageEntry> dataSet;
    public final int requestNonce;
    public final boolean isGetUpdatedDataResponse;

    @Nullable
    private final ArrayList<Integer> supportedCapabilities = Capabilities.getCapabilities();

    public GetDataResponse(HashSet<ProtectedStorageEntry> dataSet, int requestNonce, boolean isGetUpdatedDataResponse) {
        this.dataSet = dataSet;
        this.requestNonce = requestNonce;
        this.isGetUpdatedDataResponse = isGetUpdatedDataResponse;
    }

    @Override
    @Nullable
    public ArrayList<Integer> getSupportedCapabilities() {
        return supportedCapabilities;
    }

    @Override
    public int getMessageVersion() {
        return messageVersion;
    }

    @Override
    public PB.Envelope toProto() {
        PB.GetDataResponse.Builder builder = PB.GetDataResponse.newBuilder();
        builder.addAllDataSet(
                dataSet.stream()
                        .map(protectedStorageEntry -> {
                            PB.ProtectedStorageEntryOrProtectedMailboxStorageEntry.Builder builder1 =
                                    PB.ProtectedStorageEntryOrProtectedMailboxStorageEntry.newBuilder();
                            if (protectedStorageEntry instanceof ProtectedMailboxStorageEntry) {
                                builder1.setProtectedMailboxStorageEntry((PB.ProtectedMailboxStorageEntry) protectedStorageEntry.toProto());
                            } else {
                                builder1.setProtectedStorageEntry((PB.ProtectedStorageEntry) protectedStorageEntry.toProto());
                            }
                            return builder1.build();
                        })
                        .collect(Collectors.toList()))
                .setRequestNonce(requestNonce)
                .setIsGetUpdatedDataResponse(isGetUpdatedDataResponse);
        return Msg.getEnv().setGetDataResponse(builder).build();
    }


    @Override
    public String toString() {
        return "GetDataResponse{" +
                "dataSet.size()=" + dataSet.size() +
                ", isGetUpdatedDataResponse=" + isGetUpdatedDataResponse +
                ", requestNonce=" + requestNonce +
                ", supportedCapabilities=" + supportedCapabilities +
                ", messageVersion=" + messageVersion +
                '}';
    }
}