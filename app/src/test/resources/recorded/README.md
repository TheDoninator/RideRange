Real responses recorded on 2026-10-04 from the free public services the app uses, for parser tests:

- brouter_*.json: https://brouter.de/brouter (St. George, UT; from 37.0965,-113.5684), format=geojson, timode=3
  - brouter_trekking_short.json: to 37.1080,-113.5840, profile=trekking
  - brouter_{fastbike,trekking,safety}_0.json: to 37.1300,-113.5300, alternativeidx=0
  - brouter_scooter_trails.json: same, with the bundled assets/brouter/scooter-trails.brf uploaded as a custom profile
- overpass_stgeorge.json: https://overpass-api.de/api/interpreter, amenity=bicycle_parking in (37.05,-113.65,37.15,-113.50)
- nominatim_*.json: https://nominatim.openstreetmap.org search "Pioneer Park" (viewbox St. George) and reverse 37.0965,-113.5684
