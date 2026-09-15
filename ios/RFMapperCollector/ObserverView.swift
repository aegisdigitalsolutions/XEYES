import SwiftUI
import RFMapperCore
import RFMapperCollectorKit

/// Declares who is collecting, and what this device can and cannot see.
///
/// This is the screen with the most leverage in the app and the least visual interest. `observer.json`
/// is how the Lab learns to read this device's silence: an iOS observer that reports no Wi-Fi scan
/// results means *"this observer cannot scan Wi-Fi"*, not *"no access points were present"*. Get that
/// declaration wrong and every fingerprint's Wi-Fi visibility probability is driven toward zero by an
/// absence that was never evidence of anything (`docs/06-ios-capability-matrix.md` §6.2).
///
/// So the capability sets are not left to the operator to fill in. They are derived from the platform
/// and shown read-only, with the consequence of each stated next to it.
struct ObserverView: View {

    @EnvironmentObject private var model: CollectorModel

    @State private var observerId = ""
    @State private var friendlyName = ""
    @State private var buildingId = ""
    @State private var defaultZoneId = ""
    @State private var notes = ""
    @State private var newServiceUuid = ""
    @State private var hasLoaded = false

    var body: some View {
        NavigationStack {
            Form {
                identitySection
                capabilitySection
                enrolledTagsSection

                if let observer = model.observer {
                    Section("Recorded in every package") {
                        LabeledContent("Observer", value: observer.observerId)
                        LabeledContent("Device", value: observer.deviceModel ?? "unknown")
                        LabeledContent("iOS", value: observer.osVersion ?? "unknown")
                        if let installation = observer.installationId {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Installation").font(.caption).foregroundStyle(.secondary)
                                Text(installation)
                                    .font(.caption2.monospaced())
                                    .textSelection(.enabled)
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
            .navigationTitle("Observer")
            .onAppear(perform: loadOnce)
        }
    }

    // MARK: - Sections

    private var identitySection: some View {
        Section {
            TextField("Observer ID", text: $observerId)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
            TextField("Friendly name", text: $friendlyName)
            TextField("Building ID (optional)", text: $buildingId)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
            TextField("Default zone ID (optional)", text: $defaultZoneId)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
            TextField("Notes (optional)", text: $notes, axis: .vertical)

            Button("Save identity", action: save)
                .disabled(observerId.isEmpty || friendlyName.isEmpty)
        } header: {
            Text("Identity")
        } footer: {
            // Stated because the field is absent rather than merely unset, and its absence is the
            // sort of thing that looks like an oversight.
            Text(
                "The observer ID must be the same one the Master registered for this device, or its "
                    + "packages cannot be attributed. This device is never recorded as a fixed "
                    + "observer: a handheld phone's samples must not be given the weight of a "
                    + "surveyed reference point, whatever it happens to be sitting next to."
            )
        }
    }

    private var capabilitySection: some View {
        Section {
            CapabilityLine(
                capability: "BLE",
                supported: true,
                consequence: "Advertisements with RSSI. The radio iOS is strongest on."
            )
            CapabilityLine(
                capability: "GPS",
                supported: true,
                consequence: "Locates this phone, not the devices it observes."
            )
            CapabilityLine(
                capability: "WIFI_ASSOCIATION",
                supported: true,
                consequence: "The joined network's BSSID, with no signal strength."
            )
            CapabilityLine(
                capability: "WIFI_SCAN",
                supported: false,
                consequence: "Declared unsupported so the Lab reads no Wi-Fi rows as "
                    + "\"cannot see\" rather than \"nothing was there\"."
            )
            CapabilityLine(
                capability: "RTT",
                supported: false,
                consequence: "No public 802.11mc API on any iOS version."
            )
        } header: {
            Text("Capabilities")
        } footer: {
            Text(
                "Fixed by the platform, not a preference. These sets go into observer.json and "
                    + "decide how this device's silence is interpreted."
            )
        }
    }

    private var enrolledTagsSection: some View {
        Section {
            ForEach(model.enrolledServiceUuids, id: \.self) { uuid in
                Text(uuid).font(.caption.monospaced())
            }
            .onDelete { model.removeEnrolledServiceUuids(at: $0) }

            HStack {
                TextField("180D or full UUID", text: $newServiceUuid)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .font(.caption.monospaced())
                Button("Add") {
                    model.enroll(serviceUuid: newServiceUuid)
                    newServiceUuid = ""
                }
                .disabled(newServiceUuid.isEmpty)
            }
        } header: {
            Text("Enrolled service UUIDs")
        } footer: {
            // The single most useful thing this screen can tell whoever is buying the tags.
            Text(
                "Two things depend on these. Background scanning sees nothing else at all, and a "
                    + "service UUID is the only way a sighting from this iPhone can be joined to an "
                    + "Android sighting of the same tag — iOS never discloses a peripheral's "
                    + "address. A tag that advertises no service UUID can be counted here and "
                    + "matched nowhere."
            )
        }
    }

    // MARK: - Actions

    /// Fills the form from the saved identity, once, so typing is not overwritten on every appear.
    private func loadOnce() {
        guard !hasLoaded else { return }
        hasLoaded = true

        guard let existing = model.observer else { return }
        observerId = existing.observerId
        friendlyName = existing.friendlyName
        buildingId = existing.buildingId ?? ""
        defaultZoneId = existing.defaultZoneId ?? ""
        notes = existing.notes ?? ""
    }

    private func save() {
        // Built from the suggested identity rather than assembled here, so the capability sets and
        // the device fields come from one place and cannot drift from what the screen above claims.
        var identity = model.suggestedIdentity(
            observerId: observerId.trimmed(),
            friendlyName: friendlyName.trimmed()
        )
        identity.buildingId = buildingId.trimmed().isEmpty ? nil : buildingId.trimmed()
        identity.defaultZoneId = defaultZoneId.trimmed().isEmpty ? nil : defaultZoneId.trimmed()
        identity.notes = notes.trimmed().isEmpty ? nil : notes.trimmed()

        model.saveObserver(identity)
    }
}

private struct CapabilityLine: View {
    let capability: String
    let supported: Bool
    let consequence: String

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: supported ? "checkmark.circle.fill" : "xmark.circle.fill")
                .foregroundStyle(supported ? .green : .secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(capability).font(.footnote.monospaced())
                Text(consequence).font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

private extension String {
    func trimmed() -> String {
        trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
