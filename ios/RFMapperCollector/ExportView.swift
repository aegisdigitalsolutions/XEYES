import SwiftUI
import RFMapperCore
import RFMapperCollectorKit

/// Export and hand-off.
///
/// The package digest is shown rather than hidden, because it is how the operator and whoever
/// imports the package establish they are talking about the same bytes. A re-export of the same day
/// produces the same digest -- the export path is deterministic on purpose -- so a changed digest
/// means changed data, which is a question worth being able to ask.
struct ExportView: View {

    @EnvironmentObject private var model: CollectorModel

    var body: some View {
        NavigationStack {
            Form {
                Section("Stored") {
                    HStack {
                        Text("Observations")
                        Spacer()
                        Text("\(model.totalStored)").monospacedDigit().foregroundStyle(.secondary)
                    }
                }

                Section("Days") {
                    if model.days.isEmpty {
                        Text("Nothing collected yet.").foregroundStyle(.secondary)
                    } else {
                        ForEach(model.days, id: \.self) { day in
                            Button {
                                model.exportDay(day)
                            } label: {
                                HStack {
                                    Text(Iso8601.utcDateStamp(day)).monospaced()
                                    Spacer()
                                    Image(systemName: "square.and.arrow.up")
                                }
                            }
                        }
                    }
                }

                if let export = model.lastExport {
                    Section("Last export") {
                        LabeledContent("File", value: export.url.lastPathComponent)
                        LabeledContent("Observations", value: "\(export.observations)")
                        LabeledContent("Size", value: "\(export.bytes) bytes")
                        VStack(alignment: .leading, spacing: 2) {
                            Text("SHA-256").font(.caption).foregroundStyle(.secondary)
                            Text(export.sha256)
                                .font(.caption2.monospaced())
                                .textSelection(.enabled)
                        }
                        // The share sheet is the hand-off. The package is a file on disk and is
                        // sent as a file: nothing re-encodes it, so the bytes the Master imports
                        // are the bytes whose digest is shown above.
                        ShareLink(item: export.url) {
                            Label("Share package", systemImage: "square.and.arrow.up")
                        }
                    }
                }

                Section {
                    Text(
                        "A package is a zip containing manifest.json, observations.csv, "
                            + "observations.json, observer.json and checksum.txt. The Master imports "
                            + "it unchanged; the same day re-exported gives the same bytes."
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                }

                if let error = model.lastError {
                    Section {
                        Text(error).foregroundStyle(.red).font(.footnote)
                    }
                }
            }
            .navigationTitle("Export")
            .onAppear { model.refresh() }
        }
    }
}
