import MapKit

// MARK: - Auto terrain heat-map overlay
//
// When enabled, samples visible region's DEM (debounced) and pins a coloured
// heat-map overlay to it. Mirrors Android GroundOverlay behaviour.
extension MapContainerView.Coordinator {

    /// Toggle heat-map on/off. Enabling kicks off a refresh, disabling nukes it.
    func setHeatmapEnabled(_ enabled: Bool, on mv: MKMapView) {
        guard enabled != heatmapEnabled else { return }
        heatmapEnabled = enabled
        if enabled {
            scheduleHeatmapRefresh(on: mv)
        } else {
            heatmapTask?.cancel(); heatmapTask = nil
            if let o = heatmapOverlay { mv.removeOverlay(o); heatmapOverlay = nil }
        }
    }

    /// Debounced regenerate for current region. Call on main thread.
    func scheduleHeatmapRefresh(on mv: MKMapView) {
        guard heatmapEnabled else { return }
        heatmapTask?.cancel()
        let region = mv.region   // captured on main
        heatmapTask = Task { [weak self, weak mv] in
            try? await Task.sleep(nanoseconds: 500_000_000)   // debounce
            if Task.isCancelled { return }
            guard let self else { return }
            let image = await self.heatmapService.generate(region: region)
            if Task.isCancelled { return }
            guard let image, let mv else { return }
            await MainActor.run {
                guard self.heatmapEnabled else { return }
                if let old = self.heatmapOverlay { mv.removeOverlay(old) }
                let overlay = TerrainHeatmapOverlay(image: image, region: region)
                self.heatmapOverlay = overlay
                mv.addOverlay(overlay, level: .aboveLabels)
            }
        }
    }
}
