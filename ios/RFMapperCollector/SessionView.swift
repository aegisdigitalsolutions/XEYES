import SwiftUI
import RFMapperCore
import RFMapperCollectorKit

/// The collection screen.
///
/// The design decision worth naming: the limits are shown as first-class content rather than buried
/// in a help page. An operator who does not know that unfiltered BLE scanning stops when the screen
/// sleeps, or that iOS cannot scan Wi-Fi at all, will produce a dataset with unexplained holes in
/// it and no idea why. So the screen states what the selected mode can and cannot see, before the
/// session starts.
struct SessionView: View {

    @EnvironmentObject private var model: CollectorModel
    @State private var chosenProfile: ScanProfile = .foregroundSurvey

    var body: some View {
        NavigationStack {
            Form {
                if model.observer == nil {
                    Section {
                        Label(
                            "Set the observer identity on the Observer tab before collecting.",
                            systemImage: "exclamationmark.triangle"
                        )
                        .foregroundStyle(.orange)
                    }
                }

                Section("Scan profile") {
                    Picker("Profile", selection: $chosenProfile) {
                        ForEach(ScanProfile.allCases, id: \.self) { profile in
                            Text(profile.humanDescription).tag(profile)
                        }
                    }
                    .pickerStyle(.inline)
                    .disabled(model.isCollecting)
                }

                Section("What this mode can see") {
                    CapabilityRow(
                        name: "BLE advertisements",
                        detail: chosenProfile.requiresServiceFilter
                            ? "Enrolled service UUIDs only"
                            : "All nearby peripherals",
                        available: true
                    )
                    CapabilityRow(
                        name: "Repeated samples per device",
                        detail: chosenProfile.advertisesDuplicates
                            ? "Yes — a sequence of RSSI readings"
                            : "No — the system ignores the duplicates option in the background",
                        available: chosenProfile.advertisesDuplicates
                    )
                    CapabilityRow(
                        name: "Joined Wi-Fi network",
                        detail: "BSSID only, with no signal strength",
                        available: true
                    )
                    CapabilityRow(name: "GNSS", detail: "Locates this phone", available: true)
                    // Stated rather than omitted. An operator who expects Wi-Fi scanning and finds
                    // no Wi-Fi rows will otherwise assume the app is broken.
                    CapabilityRow(
                        name: "Wi-Fi scanning",
                        detail: "Not available on iOS to any third-party app",
                        available: false
                    )
                    CapabilityRow(
                        name: "Wi-Fi RTT ranging",
                        detail: "No public API on any iOS version",
                        available: false
                    )
                }

                if chosenProfile == .foregroundSurvey {
                    Section {
                        Label(
                            "The screen stays awake while collecting. Unfiltered scanning stops if "
                                + "the app leaves the foreground.",
                            systemImage: "sun.max"
                        )
                        .font(.footnote)
                    }
                }

                if chosenProfile.requiresServiceFilter && model.enrolledServiceUuids.isEmpty {
                    Section {
                        Label(
                            "Background mode needs at least one enrolled service UUID. Add one on "
                                + "the Observer tab, or nothing will be collected.",
                            systemImage: "exclamationmark.triangle"
                        )
                        .foregroundStyle(.orange)
                        .font(.footnote)
                    }
                }

                Section {
                    if model.isCollecting {
                        Button("Stop collecting", role: .destructive) { model.stop() }
                    } else {
                        Button("Start collecting") { model.start(profile: chosenProfile) }
                            .disabled(model.observer == nil)
                    }
                }

                if let summary = model.summary {
                    Section("This session") {
                        CountRow(label: "Observations", value: summary.observationCount)
                        CountRow(label: "BLE", value: summary.bleCount)
                        CountRow(label: "Wi-Fi association", value: summary.wifiCount)
                        CountRow(label: "GNSS", value: summary.gpsCount)
                        if summary.droppedSamples > 0 {
                            // Shown even though it is unflattering: a gap with a stated cause is
                            // worth far more than a clean-looking file that omits it.
                            CountRow(label: "Samples dropped", value: summary.droppedSamples)
                                .foregroundStyle(.orange)
                        }
                    }
                }

                if !model.degradations.isEmpty {
                    Section("Recorded in the package") {
                        ForEach(model.degradations, id: \.reason) { entry in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(entry.reason)
                                    .font(.footnote.monospaced())
                                if let detail = entry.detail {
                                    Text(detail)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }

                if let error = model.lastError {
                    Section {
                        Text(error).foregroundStyle(.red).font(.footnote)
                    }
                }
            }
            .navigationTitle("Collect")
        }
    }
}

private struct CapabilityRow: View {
    let name: String
    let detail: String
    let available: Bool

    var body: some View {
        HStack(alignment: .top) {
            Image(systemName: available ? "checkmark.circle.fill" : "xmark.circle.fill")
                .foregroundStyle(available ? .green : .secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

private struct CountRow: View {
    let label: String
    let value: Int64

    var body: some View {
        HStack {
            Text(label)
            Spacer()
            Text("\(value)").monospacedDigit().foregroundStyle(.secondary)
        }
    }
}
