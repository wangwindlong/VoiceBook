import SwiftUI
import WidgetKit

// 资讯小组件：读 App Group UserDefaults 里的同步快照（core:data 写入，
// suite = group.us.wangxy.voicebook）。使用前需在 Xcode 中为主 App 与
// Widget Extension 同时启用该 App Group entitlement。

struct RssSnapshot: Decodable {
    struct Item: Decodable {
        let id: String
        let title: String
        let feed: String
    }
    let unread: Int
    let items: [Item]
}

private func loadSnapshot() -> RssSnapshot? {
    guard let defaults = UserDefaults(suiteName: "group.us.wangxy.voicebook"),
          let raw = defaults.string(forKey: "rss_snapshot_json"),
          let data = raw.data(using: .utf8)
    else { return nil }
    return try? JSONDecoder().decode(RssSnapshot.self, from: data)
}

struct RssWidgetEntry: TimelineEntry {
    let date: Date
    let snapshot: RssSnapshot?
}

struct RssWidgetProvider: TimelineProvider {
    func placeholder(in context: Context) -> RssWidgetEntry {
        RssWidgetEntry(date: Date(), snapshot: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (RssWidgetEntry) -> Void) {
        completion(RssWidgetEntry(date: Date(), snapshot: loadSnapshot()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<RssWidgetEntry>) -> Void) {
        let entry = RssWidgetEntry(date: Date(), snapshot: loadSnapshot())
        completion(Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(1800))))
    }
}

struct RssWidgetEntryView: View {
    var entry: RssWidgetEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("资讯").font(.headline)
                Spacer()
                Text("未读 \(entry.snapshot?.unread ?? 0)").font(.caption).foregroundColor(.accentColor)
            }
            if let items = entry.snapshot?.items, !items.isEmpty {
                ForEach(items, id: \.id) { item in
                    Text(item.title).font(.caption).lineLimit(2)
                }
            } else {
                Text("暂无文章").font(.caption).foregroundColor(.secondary)
            }
        }
        .padding(8)
    }
}

struct RssWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "RssWidget", provider: RssWidgetProvider()) { entry in
            RssWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("资讯")
        .description("最新文章与未读数")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

// 若要启用：在 Xcode 中 File → New → Target → Widget Extension，
// 用本目录的 RssWidget.swift 替换模板，并为主 App 与 Extension
// 添加 group.us.wangxy.voicebook App Group capability。
