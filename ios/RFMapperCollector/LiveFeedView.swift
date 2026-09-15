import SwiftUI
import RFMapperCore
import RFMapperCollectorKit

/// A live view of the rows as they are captured.
///
/// Its purpose is verification during a survey, so it shows the fields that reveal a problem while
/// there is still time to fix it: which identifier was recorded, whether that identifier can be
/// joined to anything, and whether a row arrived without a signal level. A prettier summary that
/// hid the identifier scope would defeat the point.
struct LiveFeedView: View {

    @EnvironmentObject private var model: CollectorModel

    var body: some View {
        NavigationStack {
            Group {
                if model.recent.isEmpty {
                    // Hand-rolled rather than `ContentUnavailableView`, which is iOS 17. The
                    // deployment target is 16 so that a cheap second-hand handset -- an iPhone 8 or
                    // X, which cannot go past 16 -- can still be used as a survey device.
                    VStack(spacing: 8) {
                        Image(systemName: "dot.radiowaves.left.and.right")
                            .font(.largeTitle)
                            .foregroundStyle(.secondary)
                        Text("Nothing captured yet").font(.headline)
                        Text("Start a session on the Collect tab.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List(model.recent, id: \.observationId) { observation in
                        ObservationRow(observation: observation)
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Live")
        }
    }
}

private struct ObservationRow: View {

    let observation: Observation

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(observation.sensorType.rawValue)
                    .font(.caption.bold())
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(tint.opacity(0.15), in: Capsule())
                    .foregroundStyle(tint)
                Spacer()
                Text(String(observation.timestampUtc.dropFirst(11).dropLast(1)))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }

            Text(observation.radioIdentifier)
                .font(.footnote.monospaced())
                .lineLimit(1)
                .truncationMode(.middle)

            HStack(spacing: 10) {
                if let rssi = observation.rssi {
                    Text("\(rssi) dBm").font(.caption.monospacedDigit())
                } else if observation.sensorType == .wifiAssociation {
                    // Not a fault, and the row should not look like one. iOS does not disclose the
                    // joined network's signal strength; the association itself is the evidence.
                    Text("no RSSI on iOS")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                if let ssid = observation.ssid {
                    Text(ssid).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }

                if observation.metadata[MetadataKeys.identifierScope]
                    == IdentifierScope.appInstall.rawValue {
                    // The most important thing an operator can know about an iOS BLE row: this
                    // identifier means nothing to any other device.
                    Label("app-install id", systemImage: "person.crop.circle.badge.questionmark")
                        .font(.caption2)
                        .foregroundStyle(.orange)
                }

                if observation.bleServiceUuid != nil {
                    Label("joinable", systemImage: "link")
                        .font(.caption2)
                        .foregroundStyle(.green)
                }

                if let device = observation.targetDeviceId {
                    Label(device, systemImage: "tag")
                        .font(.caption2)
                        .foregroundStyle(.blue)
                }
            }
        }
        .padding(.vertical, 2)
    }

    private var tint: Color {
        switch observation.sensorType {
        case .ble: return .purple
        case .wifiAssociation, .wifiScan: return .blue
        case .gps: return .green
        case .rtt: return .orange
        default: return .gray
        }
    }
}
