# Awesome Ocean Science [![Awesome](https://awesome.re/badge.svg)](https://awesome.re)

> A curated list of software, models, data, maps, institutes and tools for understanding the ocean.

Maintained by [marola-dev](https://github.com/marola-dev), the people behind
[marola](https://github.com/marola-dev/marola), an open, citizen-science ocean intelligence layer
for the Brazilian coast. Entries lean towards things that are open, free to use, or run locally,
and Brazil and the South Atlantic get their own sections. Every link was checked when it was added
(2026-10-02); licences are noted where they limit reuse.

## Contents

- [AI weather and ocean models](#ai-weather-and-ocean-models)
  - [Google WeatherNext](#google-weathernext)
  - [Weather and Earth-system models](#weather-and-earth-system-models)
  - [Ocean models](#ocean-models)
  - [AI wave models vs. WAVEWATCH III](#ai-wave-models-vs-wavewatch-iii)
- [Numerical ocean models](#numerical-ocean-models)
- [Wave models](#wave-models)
- [Libraries](#libraries)
- [Data portals and reanalyses](#data-portals-and-reanalyses)
- [Observing systems and buoys](#observing-systems-and-buoys)
- [APIs](#apis)
- [Maps and visualizers](#maps-and-visualizers)
- [Institutes](#institutes)
- [Brazil and the South Atlantic](#brazil-and-the-south-atlantic)
- [On Instagram](#on-instagram)
- [Ocean sound](#ocean-sound)
- [Speech to text and text to speech](#speech-to-text-and-text-to-speech)
- [Citizen science](#citizen-science)
- [Learning](#learning)
- [Related lists](#related-lists)
- [Inbox](#inbox)

## AI weather and ocean models

### Google WeatherNext

- [WeatherNext](https://github.com/google-deepmind/weathernext) - Google DeepMind code and weights for WeatherNext 2, WeatherNext Cyclones and their 1° "mini" variants, with GraphCast and GenCast as the earlier generation (Apache-2.0 code, CC BY 4.0 weights).
- [WeatherNext 3](https://deepmind.google/science/weathernext/) - Hourly-initialised global AI forecast at up to 0.05° (~5 km), driven directly by geostationary satellite observations; a hosted data product, not open weights.
- [Introducing WeatherNext 3](https://blog.google/innovation-and-ai/models-and-research/google-deepmind/introducing-weathernext-3/) - The 2026-09-03 launch post, covering the rollout to Search, Gemini, Maps and Google Cloud.
- [WeatherNext developer guides](https://developers.google.com/weathernext/guides/models) - The model family, and how to get forecasts through [BigQuery](https://developers.google.com/weathernext/guides/bigquery), [Earth Engine](https://developers.google.com/weathernext/guides/earth-engine) or Cloud Storage after a data request.
- [Weather Lab](https://developers.google.com/weathernext/guides/weatherlab) - DeepMind's experimental site showing WeatherNext layers and cyclone tracks, with CSV/ATCF downloads.

### Weather and Earth-system models

- [Aurora](https://github.com/microsoft/aurora) - Microsoft's 1.3B-parameter Earth-system foundation model, fine-tuned for weather, air quality, tropical cyclones and 0.25° global ocean waves (MIT).
- [ECMWF AIFS](https://huggingface.co/ecmwf/aifs-single-1.1) - Open weights of ECMWF's operational AI forecasting system (CC BY 4.0).
- [Anemoi](https://github.com/ecmwf/anemoi-core) - ECMWF-led framework used to train AIFS and other data-driven weather models; run them with [anemoi-inference](https://github.com/ecmwf/anemoi-inference) (Apache-2.0).
- [Earth2Studio](https://github.com/NVIDIA/earth2studio) - NVIDIA Earth-2 framework for AI weather and climate inference across many models and data sources (Apache-2.0).
- [FourCastNet](https://github.com/NVlabs/FourCastNet) - NVIDIA's 0.25° global weather model based on Adaptive Fourier Neural Operators.
- [Makani](https://github.com/NVIDIA/makani) - NVIDIA/NERSC library for large-scale training of ML weather and climate models such as SFNO.
- [PhysicsNeMo](https://github.com/NVIDIA/physicsnemo) - NVIDIA's PyTorch framework for physics-ML models, with weather recipes (Apache-2.0).
- [Pangu-Weather](https://github.com/198808xc/Pangu-Weather) - Huawei Cloud's 3D Earth-specific transformer for medium-range forecasting (weights CC BY-NC-SA 4.0).
- [FuXi](https://github.com/tpys/FuXi) - Fudan University's cascaded ML system for 15-day global forecasts.
- [NeuralGCM](https://github.com/neuralgcm/neuralgcm) - Hybrid ML and differentiable-physics atmospheric model for weather and climate (Apache-2.0).
- [WeatherBench 2](https://github.com/google-research/weatherbench2) - Benchmark and evaluation code for data-driven global weather models (Apache-2.0).

### Ocean models

- [Samudra](https://github.com/m2lines/samudra) - M2LInES emulator of a climate model's full-depth global ocean, stable over centuries (Apache-2.0).
- [GLONET](https://arxiv.org/abs/2412.05454) - Mercator Ocean's neural global ocean forecast trained on GLORYS12.
- [XiHe](https://arxiv.org/abs/2402.02995) - Eddy-resolving 1/12° global ocean forecast model built on hierarchical transformers.
- [WenHai](https://doi.org/10.1038/s41467-025-57389-2) - Eddy-resolving global ocean forecast network with bulk air–sea flux formulae inside it (Nature Communications, 2025).
- [FuXi-Ocean](https://arxiv.org/abs/2506.03210) - Six-hourly 1/12° global ocean forecast down to 1500 m (NeurIPS 2025).
- [LangYa](https://arxiv.org/abs/2412.18097) - 1/12° ocean forecasting system for 1–7-day lead times.
- [ORCA-DL](https://github.com/OpenEarthLab/ORCA) - Data-driven global ocean model for seasonal-to-decadal prediction.
- [SeaCast](https://github.com/deinal/seacast) - Hierarchical graph neural network for high-resolution regional ocean forecasting, shown on the Mediterranean (MIT).

### AI wave models vs. WAVEWATCH III

Physics wave models such as WW3 are what most public wave forecasts still run on. These works test
learned models against them.

- [Aurora: A Foundation Model for the Earth System](https://arxiv.org/abs/2405.13063) - Fine-tuned Aurora produces 10-day 0.25° global wave forecasts that beat ECMWF HRES-WAM on 86% of targets (Nature, 2025).
- [Representing ocean wind waves in ECMWF's AIFS](https://www.ecmwf.int/en/about/media-centre/aifs-blog/2025/representing-ocean-wind-waves-ecmwfs-aifs) - Adding wave variables to AIFS gives significant wave height competitive with ecWAM, about a day better in the medium range (ECMWF blog, 2025).
- [Ocean Wave Forecasting with Deep Learning as Alternative to Conventional Models](https://arxiv.org/abs/2406.03848) - OceanCastNet, an energy-balanced AFNO trained on ERA5; its authors report it beats WW3 and matches ECWAM against NDBC buoys and Jason-3.
- [Using AI to enable improved physics in an ocean wave model](https://e3sm.org/using-ai-to-enable-improved-physics-in-an-ocean-wave-model/) - A neural emulator of the exact four-wave interaction term inside WW3, up to 136x faster than WRT and about 2x more accurate than DIA.
- [Data-driven rolling model for global wave height](https://gmd.copernicus.org/articles/18/5101/2025/) - Autoregressive deep-learning Hs model under wind forcing that mimics a numerical wave model at a fraction of the cost (GMD, 2025).
- [WaveGraph](https://arxiv.org/abs/2608.16449) - Multiscale GNN on an unstructured mesh reproducing a reference wave model for 17 years of the Mediterranean at ~2.5 minutes per simulated year (2026).

## Numerical ocean models

- [ROMS](https://github.com/myroms/roms) - Regional Ocean Modeling System, a free-surface, terrain-following community model (MIT).
- [MOM6](https://github.com/mom-ocean/MOM6) - NOAA-GFDL's Modular Ocean Model with ALE vertical coordinates (Apache-2.0).
- [HYCOM](https://github.com/HYCOM/HYCOM-src) - HYbrid Coordinate Ocean Model, behind many operational global forecasts (MIT).
- [MITgcm](https://github.com/MITgcm/MITgcm) - MIT General Circulation Model, with an adjoint for data assimilation (MIT).
- [MPAS-Ocean](https://github.com/MPAS-Dev/MPAS-Model) - Unstructured Voronoi-mesh ocean core of the Model for Prediction Across Scales.
- [FVCOM](https://github.com/FVCOM-GitHub/FVCOM) - Finite-Volume Community Ocean Model for coasts and estuaries on unstructured grids (MIT).
- [SCHISM](https://github.com/schism-dev/schism) - Cross-scale 3D baroclinic model from creek to ocean on unstructured grids (Apache-2.0).
- [ADCIRC](https://github.com/adcirc/adcirc) - Finite-element circulation model widely used for storm surge and tides.
- [Delft3D](https://github.com/Deltares/Delft3D) - Deltares engines for Delft3D 4 and Delft3D FM, including D-Flow FM hydrodynamics.
- [COAWST](https://github.com/DOI-USGS/COAWST) - USGS coupled ocean–atmosphere–wave–sediment system joining ROMS, WRF, SWAN/WW3 and sediment models.
- [Oceananigans.jl](https://github.com/CliMA/Oceananigans.jl) - Julia library for fast ocean-flavoured fluid dynamics on CPUs and GPUs (MIT); [ClimaOcean.jl](https://github.com/CliMA/ClimaOcean.jl) builds regional-to-global ocean–sea-ice runs on it.
- [Veros](https://github.com/team-ocean/veros) - Primitive-equation ocean model in pure Python on JAX (GPL-3.0).
- [GOTM](https://github.com/gotm-model/code) - General Ocean Turbulence Model, a 1D water-column model (GPL-2.0).
- [Thetis](https://github.com/thetisproject/thetis) - Firedrake-based finite-element solver for coastal and estuarine flows (MIT).

## Wave models

- [WAVEWATCH III](https://github.com/NOAA-EMC/WW3) - NOAA-EMC's third-generation spectral wind-wave model; it is the wave component of NCEP's GFS-Wave.
- [ecWAM](https://github.com/ecmwf-ifs/ecwam) - ECMWF's wave model, the wave component of the IFS (Apache-2.0).
- [WAM](https://github.com/myWAveModel/WAM) - The third-generation WAve Model, Cycle 7, maintained at Helmholtz-Zentrum Hereon (GPL-3.0).
- [SWAN](https://sourceforge.net/projects/swanmodel/) - TU Delft spectral wave model for coasts, estuaries, reefs and lakes (GPL-3.0).
- [SWASH](https://sourceforge.net/projects/swash/) - Non-hydrostatic, phase-resolving wave–flow model for harbours and coastal waters (GPL-3.0).
- [XBeach](https://github.com/openearth/xbeach) - Deltares model for nearshore waves and storm impacts on sandy coasts; a read-only mirror of the Deltares SVN.
- [FUNWAVE-TVD](https://github.com/fengyanshi/FUNWAVE-TVD) - Fully nonlinear Boussinesq model for nearshore waves, runup and tsunamis.
- [REEF3D](https://github.com/REEF3D/REEF3D) - CFD and phase-resolved wave models for marine and coastal engineering (GPL-3.0).

## Libraries

- [xarray](https://github.com/pydata/xarray) - Labelled N-dimensional arrays, the base of most NetCDF and Zarr ocean workflows (Apache-2.0).
- [Pangeo](https://pangeo.io/) - Community stack for big-data geoscience on xarray, Dask and Zarr.
- [xgcm](https://github.com/xgcm/xgcm) - Grid-aware interpolation, differencing and integration on staggered model grids (MIT).
- [xESMF](https://github.com/pangeo-data/xESMF) - Regridding for geospatial data on ESMF (MIT).
- [cf-xarray](https://github.com/xarray-contrib/cf-xarray) - Use CF-convention attributes directly on xarray objects (Apache-2.0).
- [intake-esm](https://github.com/intake/intake-esm) - Search Earth System Model catalogs and load results into xarray (Apache-2.0).
- [Parcels](https://github.com/Parcels-code/Parcels) - Lagrangian particle tracking on ocean model output (MIT).
- [CloudDrift](https://github.com/Cloud-Drift/clouddrift) - Drifter and float trajectory data as ragged arrays (MIT).
- [GSW-Python](https://github.com/TEOS-10/GSW-Python) - TEOS-10 seawater thermodynamics; also as [GibbsSeaWater.jl](https://github.com/TEOS-10/GibbsSeaWater.jl) for Julia.
- [cmocean](https://github.com/matplotlib/cmocean) - Perceptually uniform colormaps for ocean variables (MIT).
- [argopy](https://github.com/euroargodev/argopy) - Access and analyse Argo float data in Python (EUPL-1.2).
- [Copernicus Marine Toolbox](https://github.com/mercator-ocean/copernicus-marine-toolbox) - Official CLI and Python API to subset and download Copernicus Marine data (EUPL-1.2).
- [erddapy](https://github.com/ioos/erddapy) - Build ERDDAP search and download requests from Python (BSD-3-Clause).
- [pyTMD](https://github.com/pyTMD/pyTMD) - Ocean, load, solid-Earth and pole tide prediction (MIT).
- [wavespectra](https://github.com/wavespectra/wavespectra) - Ocean wave spectra on xarray, reading WW3, SWAN, ERA5 and NDBC formats (MIT).
- [MetPy](https://github.com/Unidata/MetPy) - Read, compute with and plot weather data (BSD-3-Clause).
- [OceanSpy](https://github.com/hainegroup/oceanspy) - Analyse and visualise ocean model output, especially MITgcm (MIT).
- [xroms](https://github.com/xoceanmodel/xroms) - Work with ROMS output in xarray (MIT).
- [oce](https://github.com/dankelley/oce) - R package for CTD, ADCP, sea level and satellite data (GPL-3.0).
- [rerddap](https://github.com/ropensci/rerddap) - R client for ERDDAP servers (MIT).
- [argoFloats](https://github.com/ArgoCanada/argoFloats) - R package for collections of Argo float data.

## Data portals and reanalyses

- [Copernicus Marine Data Store](https://data.marine.copernicus.eu) - EU catalogue of satellite, in situ, reanalysis and forecast ocean products.
- [WAVERYS](https://data.marine.copernicus.eu/product/GLOBAL_MULTIYEAR_WAV_001_032) - Copernicus global wave reanalysis from 1980 at 0.2°, on MFWAM with altimetry assimilation.
- [ERA5](https://cds.climate.copernicus.eu/datasets/reanalysis-era5-single-levels) - ECMWF's hourly atmosphere and ocean-wave reanalysis from 1940, on the Copernicus Climate Data Store.
- [NOAA NCEI](https://www.ncei.noaa.gov/) - NOAA's archive of ocean, coastal, climate and geophysical data.
- [World Ocean Database](https://www.ncei.noaa.gov/products/world-ocean-database) - The largest quality-controlled public collection of ocean profiles, from 1772 on.
- [NOAA CoastWatch ERDDAP](https://coastwatch.pfeg.noaa.gov/erddap/index.html) - Satellite and in situ datasets with subsetting and downloads in many formats.
- [NASA PO.DAAC](https://podaac.jpl.nasa.gov/) - NASA's physical oceanography archive: SST, salinity, sea surface height, ocean winds.
- [NASA Earthdata – Ocean](https://www.earthdata.nasa.gov/topics/ocean) - Entry point to NASA's ocean datasets and tutorials (free login).
- [GEBCO](https://www.gebco.net/data_and_products/gridded_bathymetry_data) - Global 15 arc-second bathymetry and terrain grid, released yearly.
- [EMODnet](https://emodnet.ec.europa.eu/) - EU marine data on bathymetry, geology, physics, chemistry, biology and human activity.
- [OBIS](https://obis.org/) - Where and when marine species were recorded, under IOC-UNESCO's IODE.
- [GBIF](https://www.gbif.org/) - Global species occurrence records, marine taxa included.
- [Allen Coral Atlas](https://allencoralatlas.org/) - Satellite maps of shallow coral reefs, with bleaching and turbidity monitoring.
- [SOCAT](https://socat.info/) - Quality-controlled surface ocean CO₂ measurements behind ocean carbon sink estimates.
- [Global Fishing Watch data](https://globalfishingwatch.org/global-fishing-watch-data-availability/) - Fishing-effort and vessel-presence datasets (free registration).
- [GOOS](https://goosocean.org/) - The IOC-led programme coordinating sustained global ocean observation.
- [U.S. IOOS](https://ioos.noaa.gov/) - NOAA-led network of regional ocean observing systems and their data.

## Observing systems and buoys

- [Argo](https://argo.ucsd.edu/) - Thousands of profiling floats measuring upper-ocean temperature and salinity, open within 24 hours.
- [NOAA National Data Buoy Center](https://www.ndbc.noaa.gov/) - Real-time observations from NOAA's moored buoys and coastal stations.
- [CDIP](https://cdip.ucsd.edu/) - Scripps wave buoy network with real-time height, period and direction.
- [Global Drifter Program](https://www.aoml.noaa.gov/phod/gdp/) - About 1,300 satellite-tracked drifters measuring currents, SST and pressure.
- [OceanSITES](https://ocean-ops.org/oceansites) - Long-term open-ocean reference stations from the air–sea interface to the seafloor.
- [Ocean Observatories Initiative](https://oceanobservatories.org/) - Cabled arrays, moorings and gliders streaming near-real-time data.
- [NOAA Tides & Currents](https://tidesandcurrents.noaa.gov/) - U.S. water level, current and meteorological observations and tide predictions.
- [UHSLC](https://uhslc.soest.hawaii.edu/) - University of Hawaii Sea Level Center tide gauges and data.
- [PSMSL](https://psmsl.org/) - Monthly and annual mean sea level from more than 2,000 tide gauges.
- [IOC Sea Level Station Monitoring Facility](https://www.ioc-sealevelmonitoring.org/) - Real-time status of global sea level stations, tsunami networks included.
- [Saildrone data](https://www.saildrone.com/data) - Metocean data from uncrewed surface vehicles (commercial).
- [Sofar Spotter](https://www.sofarocean.com/products/spotter) - Solar-powered wave buoy streaming over satellite (commercial).

## APIs

- [Open-Meteo Marine Weather API](https://open-meteo.com/en/docs/marine-weather-api) - Keyless hourly waves, swell, SST, currents and sea level, free for non-commercial use; it is what marola reads.
- [NOAA CO-OPS Data API](https://tidesandcurrents.noaa.gov/api/) - Keyless U.S. water levels, tide and current predictions.
- [ERDDAP RESTful services](https://coastwatch.pfeg.noaa.gov/erddap/rest.html) - URL-based queries against any ERDDAP server, returning CSV, JSON, NetCDF or images.
- [Copernicus Marine Toolbox docs](https://toolbox-docs.marine.copernicus.eu/) - Search, subset and download Copernicus Marine datasets as Zarr or NetCDF.
- [Copernicus CDS API](https://cds.climate.copernicus.eu/how-to-api) - Programmatic access to ERA5 and the rest of the Climate Data Store via `cdsapi`.
- [OBIS API](https://api.obis.org/) - REST API for marine species occurrences, checklists and statistics.
- [GBIF API](https://techdocs.gbif.org/en/openapi/) - OpenAPI-documented occurrence, species and dataset search.
- [Global Fishing Watch APIs](https://globalfishingwatch.org/our-apis/) - Vessel identity, fishing events and gridded effort (token).
- [NCEI Data Service API](https://www.ncei.noaa.gov/support/access-data-service-api-user-documentation) - Programmatic access to NCEI archives.
- [Google Maps Platform Weather API](https://developers.google.com/maps/documentation/weather) - Current conditions, hourly and daily forecasts and alerts (paid, keyed).
- [Windy API](https://api.windy.com/map-forecast/docs) - Embeddable forecast map layers and a point forecast API (keyed).
- [Stormglass](https://stormglass.io/) - Aggregated marine forecasts, tides and history from many models (commercial).
- [WorldTides](https://www.worldtides.info/apidocs) - Worldwide tide heights, extremes and datums (commercial).
- [Sofar Spotter API](https://docs.sofarocean.com/) - Wave, wind and SST data from Spotter buoys you can access.

## Maps and visualizers

- [Windy](https://www.windy.com/) - Animated global forecast map with wind, waves, swell and many more layers from several models.
- [earth.nullschool.net](https://earth.nullschool.net/) - Animated globe of wind, currents, waves and SST.
- [Ventusky](https://www.ventusky.com/) - Weather map with animated wave propagation from Météo-France MFWAM.
- [NASA Worldview](https://worldview.earthdata.nasa.gov/) - More than 1,200 near-real-time and historical satellite imagery layers.
- [Copernicus MyOcean Viewer](https://myocean.marine.copernicus.eu/data) - 4D viewer for Copernicus Marine products.
- [Global Fishing Watch map](https://globalfishingwatch.org/our-map) - Vessel activity at sea: apparent fishing, encounters and SAR detections.
- [NOAA STAR OceanView](https://www.star.nesdis.noaa.gov/socd/ov/) - Viewer and event tracker for NOAA satellite ocean products.
- [NOAA Satellite Maps](https://www.nesdis.noaa.gov/real-time-imagery/interactive-maps) - Real-time geostationary and polar-orbiting imagery.
- [CoastWatch Browsers](https://coastwatch.pfeg.noaa.gov/browsers/) - Browse NOAA satellite ocean data.
- [marola.dev](https://marola.dev/) - Beaches around Florianópolis, Rio de Janeiro and Salvador ranked by best hour to swim, with official water quality.

## Institutes

- [Woods Hole Oceanographic Institution](https://www.whoi.edu/) - Independent US ocean research institution; operates Alvin, Jason and Sentry.
- [Scripps Institution of Oceanography](https://scripps.ucsd.edu/) - UC San Diego's ocean, earth and atmospheric science school, founded 1903.
- [MBARI](https://www.mbari.org/) - Monterey Bay Aquarium Research Institute, deep-sea science and ocean technology.
- [Lamont-Doherty Earth Observatory](https://lamont.columbia.edu/) - Columbia University's Earth-system research institution.
- [NOAA](https://www.noaa.gov/) - US agency for weather, ocean, coast and fisheries; see also [National Ocean Service](https://oceanservice.noaa.gov/) and [PMEL](https://www.pmel.noaa.gov/).
- [NASA Ocean Physics](https://science.nasa.gov/oceanography/) - NASA's programme for ocean dynamics and sea level from space.
- [Schmidt Ocean Institute](https://schmidtocean.org/) - Offers R/V Falkor (too) to scientists for free, in exchange for open results, with livestreamed ROV dives.
- [OceanX](https://oceanx.org/) - Ocean research, education and media aboard OceanXplorer.
- [Ocean Networks Canada](https://www.oceannetworks.ca/) - Cabled NEPTUNE and VENUS observatories, open data through Oceans 3.0.
- [Monterey Bay Aquarium](https://www.montereybayaquarium.org/) - Public aquarium focused on conservation; home of Seafood Watch.
- [National Oceanography Centre](https://noc.ac.uk/) - UK national centre for ocean science and technology.
- [Plymouth Marine Laboratory](https://pml.ac.uk/) - UK marine research charity on biogeochemistry, ecosystems and Earth observation.
- [Ifremer](https://www.ifremer.fr/) - French national institute for marine research.
- [Mercator Ocean International](https://mercator-ocean.eu/) - Ocean forecasting centre that runs the [Copernicus Marine Service](https://marine.copernicus.eu/).
- [ECMWF](https://www.ecmwf.int/) - European centre for medium-range weather, ocean-wave and ocean forecasts.
- [GEOMAR](https://www.geomar.de/) - Helmholtz Centre for Ocean Research Kiel.
- [Alfred Wegener Institute](https://www.awi.de/) - Helmholtz Centre for Polar and Marine Research.
- [CSIRO Oceans](https://www.csiro.au/en/oceans) - Ocean and atmosphere research at Australia's national science agency.
- [IMOS](https://imos.org.au/) - Australia's Integrated Marine Observing System; data on the [AODN Portal](https://portal.aodn.org.au/).
- [JAMSTEC](https://www.jamstec.go.jp/e/) - Japan Agency for Marine-Earth Science and Technology.
- [IOC-UNESCO](https://www.ioc.unesco.org/en) - Coordinates international ocean science, observation and tsunami warning.
- [UN Ocean Decade](https://oceandecade.org/) - The UN Decade of Ocean Science for Sustainable Development, 2021–2030.

## Brazil and the South Atlantic

- [Instituto Oceanográfico da USP](https://www.io.usp.br/) - University of São Paulo's oceanographic institute, rooted in Brazil's first oceanographic institution (1946).
- [Instituto de Oceanografia – FURG](https://io.furg.br/) - Federal University of Rio Grande; Brazil's first undergraduate oceanology course (1970).
- [PPG Oceanografia – UFSC](https://ppgoceano.paginas.ufsc.br/) - Federal University of Santa Catarina's graduate programme in oceanography.
- [LAMCE/COPPE-UFRJ](https://www.lamce.coppe.ufrj.br/) - UFRJ laboratory for computational methods, including coastal and ocean modelling.
- [INPE](https://www.gov.br/inpe/pt-br) - National Institute for Space Research: Earth observation, weather and climate.
- [Centro de Hidrografia da Marinha](https://www.marinha.mil.br/chm/) - Brazilian Navy charts, marine weather and operational oceanography.
- [REMO](https://www.rederemo.org/) - Navy–Petrobras network for ocean modelling and forecasts of the South Atlantic.
- [GOOS-Brasil](https://www.goosbrasil.org/) - Brazil's ocean observing system, with PNBOIA buoy and PIRATA data.
- [SiMCosta](https://simcosta.furg.br/) - Brazilian coastal monitoring network of buoys and platforms with real-time data.
- [INEA](https://www.inea.rj.gov.br/) - Rio de Janeiro State environmental agency; publishes bathing-water bulletins.
- [IMA/SC Balneabilidade](https://balneabilidade.ima.sc.gov.br/) - Santa Catarina's weekly bathing-water quality by sampling point.
- [Projeto TAMAR](https://www.tamar.org.br/) - Sea turtle conservation on about 1,100 km of coast since 1980.
- [Instituto Baleia Jubarte](https://www.baleiajubarte.org.br/) - Humpback whale research and conservation in Bahia and Espírito Santo.
- [Projeto Coral Vivo](https://coralvivo.org.br/) - Research and education on Brazilian coral reefs.
- [SIMBA (PMP-BS)](https://simba.petrobras.com.br/) - Strandings of seabirds, turtles and marine mammals from the Santos Basin beach monitoring project since 2015.

## On Instagram

Science-communication accounts worth following, starting from [@whoi.ocean](https://www.instagram.com/whoi.ocean/).

- [@whoi.ocean](https://www.instagram.com/whoi.ocean/) - Woods Hole Oceanographic Institution.
- [@scripps_ocean](https://www.instagram.com/scripps_ocean/) - Scripps Institution of Oceanography; its aquarium is [@birchaquarium](https://www.instagram.com/birchaquarium/).
- [@mbari_news](https://www.instagram.com/mbari_news/) - MBARI deep-sea research.
- [@montereybayaquarium](https://www.instagram.com/montereybayaquarium/) - Monterey Bay Aquarium.
- [@schmidtocean](https://www.instagram.com/schmidtocean/) - Schmidt Ocean Institute and R/V Falkor (too).
- [@nautiluslive](https://www.instagram.com/nautiluslive/) - Ocean Exploration Trust's E/V Nautilus expeditions.
- [@oceanx](https://www.instagram.com/oceanx/) - OceanX and OceanXplorer.
- [@noaaoceanexploration](https://www.instagram.com/noaaoceanexploration/) - NOAA Ocean Exploration and Okeanos Explorer.
- [@noaa](https://www.instagram.com/noaa/) - NOAA; also [@noaaocean](https://www.instagram.com/noaaocean/), [@noaafisheries](https://www.instagram.com/noaafisheries/), [@noaaresearch](https://www.instagram.com/noaaresearch/) and [@noaapmel](https://www.instagram.com/noaapmel/).
- [@nasaearth](https://www.instagram.com/nasaearth/) - NASA Earth science.
- [@nocnews](https://www.instagram.com/nocnews/) - UK National Oceanography Centre.
- [@ifremer_officiel](https://www.instagram.com/ifremer_officiel/) - Ifremer.
- [@geomarkiel](https://www.instagram.com/geomarkiel/) - GEOMAR Kiel.
- [@mercator_ocean](https://www.instagram.com/mercator_ocean/) - Mercator Ocean International.
- [@ocean_networks](https://www.instagram.com/ocean_networks/) - Ocean Networks Canada.
- [@oceana](https://www.instagram.com/oceana/) - Oceana, ocean conservation advocacy.
- [@institutooceanograficousp](https://www.instagram.com/institutooceanograficousp/) - Instituto Oceanográfico da USP; its museum is [@museu.iousp](https://www.instagram.com/museu.iousp/).
- [@projeto_tamar_oficial](https://www.instagram.com/projeto_tamar_oficial/) - Projeto TAMAR.

## Ocean sound

- [Orcasound](https://live.orcasound.net/) - Live hydrophone streams from Southern Resident killer whale habitat.
- [OrcaHello](https://github.com/orcasound/aifororcas-livesystem) - Real-time AI detection of killer whale calls on Orcasound hydrophones (MIT).
- [Perch](https://github.com/google-research/perch) - Google's bioacoustics models, including SurfPerch for coral reef sound (Apache-2.0); [Perch Hoplite](https://github.com/google-research/perch-hoplite) builds custom classifiers fast.
- [Google multispecies whale model](https://research.google/blog/whistles-songs-boings-and-biotwangs-recognizing-whale-vocalizations-with-ai/) - Classifier for eight whale species, Bryde's whale "biotwangs" included.
- [Pattern Radio](https://experiments.withgoogle.com/patternradio) - Explore 8,000+ hours of AI-labelled Hawaiian humpback recordings.
- [MBARI Pacific Sound](https://registry.opendata.aws/pacific-sound/) - Continuous deep-sea recordings since 2015 on AWS Open Data; [notebooks](https://github.com/mbari-org/pacific-sound-notebooks) detect blue, fin and humpback calls.
- [NCEI Passive Acoustic Data](https://www.ncei.noaa.gov/maps/passive-acoustic-data/) - NOAA's archive of hundreds of terabytes of underwater sound, [SanctSound](https://sanctuaries.noaa.gov/science/monitoring/sound/sanctsound-storymap.html) included.
- [Ocean Networks Canada Oceans 3.0](https://data.oceannetworks.ca/DataSearch) - Open data from cabled observatories, including 22 hydrophones.
- [Watkins Marine Mammal Sound Database](https://cis.whoi.edu/science/B/whalesounds/about.cfm) - WHOI's archive of 60+ species over seven decades (non-commercial use).
- [PAMGuard](https://www.pamguard.org/) - Detection, classification and localisation of marine mammal sounds (GPL-3.0).
- [Raven](https://ravensoundsoftware.com/) - Cornell's sound visualisation and measurement software.
- [Ketos](https://docs.meridian.cs.dal.ca/ketos/) - Deep-learning detectors and classifiers for underwater sound (GPL-3.0).
- [OpenSoundscape](https://github.com/kitzeslab/opensoundscape) - Bioacoustic spectrograms, CNN training and localisation in Python (MIT).

## Speech to text and text to speech

For voice notes from the beach and for transcribing ocean-science videos. Portuguese support is
noted because it matters on the Brazilian coast.

### Speech to text

- [Whisper](https://github.com/openai/whisper) - OpenAI's multilingual speech recognition, Portuguese included (MIT).
- [faster-whisper](https://github.com/SYSTRAN/faster-whisper) - Whisper on CTranslate2, up to 4x faster with less memory (MIT).
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) - Dependency-free C/C++ Whisper for CPU, Apple Silicon, GPUs, phones and Raspberry Pi (MIT).
- [WhisperX](https://github.com/m-bain/whisperX) - Whisper with word-level timestamps and speaker diarization (BSD-2-Clause).
- [Canary-1B-v2](https://huggingface.co/nvidia/canary-1b-v2) - NVIDIA recognition and translation across 25 European languages, Portuguese included (CC BY 4.0); see also [Parakeet-TDT-0.6B-v3](https://huggingface.co/spaces/nvidia/parakeet-tdt-0.6b-v3).
- [Voxtral](https://arxiv.org/abs/2507.13264) - Mistral's speech-understanding models for transcription and audio Q&A, Portuguese included (Apache-2.0).
- [Vosk](https://github.com/alphacep/vosk-api) - Offline recognition with small models in 20+ languages, Portuguese included (Apache-2.0).
- [Moonshine](https://github.com/moonshine-ai/moonshine) - Low-latency on-device speech recognition (MIT).

### Text to speech

- [Piper](https://github.com/OHF-Voice/piper1-gpl) - Fast local neural TTS with pt_BR and pt_PT voices (GPL-3.0).
- [Kokoro](https://github.com/hexgrad/kokoro) - 82M-parameter open-weight TTS with Brazilian Portuguese voices (Apache-2.0).
- [Chatterbox](https://github.com/resemble-ai/chatterbox) - Voice-cloning TTS whose multilingual model covers pt-BR and pt-PT; output is watermarked (MIT).
- [Coqui TTS](https://github.com/idiap/coqui-ai-TTS) - Community fork of Coqui TTS with XTTS-v2 (MPL-2.0 code; XTTS-v2 weights non-commercial).
- [F5-TTS](https://github.com/SWivid/F5-TTS) - Flow-matching TTS (MIT code, CC BY-NC weights).

### Runtimes and pipelines

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) - Offline runtime for Whisper, Parakeet, Moonshine, Piper and Kokoro on desktop, mobile and ARM (Apache-2.0).
- [Speaches](https://github.com/speaches-ai/speaches) - Self-hosted, OpenAI-API-compatible server pairing faster-whisper with Kokoro and Piper (MIT).
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) - Downloads audio and video from thousands of sites, Instagram reels included with logged-in cookies; pipe the audio into Whisper (Unlicense).
- [Instaloader](https://github.com/instaloader/instaloader) - Downloads Instagram posts, reels and captions (MIT). Instagram's [Terms of Use](https://help.instagram.com/581066165581870) forbid automated collection without permission, so keep to content you have rights to, one item at a time.

## Citizen science

- [iNaturalist](https://www.inaturalist.org/) - Record and identify biodiversity, marine species included.
- [Zooniverse](https://www.zooniverse.org/) - Volunteer classification projects, many with marine imagery and audio.
- [Secchi Disk](http://www.secchidisk.org/) - Seafarers measure phytoplankton with a home-made disk and a free app.
- [Smartfin](https://smartfin.org/) - A surfboard fin that logs nearshore temperature and motion while you surf.
- [CoastSnap](https://www.coastsnap.com/) - Smartphone photos from fixed cradles turned into shoreline maps.
- [Happywhale](https://happywhale.com/) - Identify individual whales from fluke photos.
- [Reef Check](https://reefcheck.org/) - Volunteer divers monitoring coral reefs and kelp forests in 40+ countries.
- [REEF](https://www.reef.org/) - Volunteer fish surveys by divers and snorkelers since 1993.
- [CoralWatch](https://coralwatch.org/about/) - Coral bleaching monitoring with the Coral Health Chart.
- [eOceans](https://eoceans.co/) - Ocean observation app for sharks, rays, turtles and more.
- [Debris Tracker](https://debristracker.org/) - Log marine litter into an open dataset.
- [Litterati](https://www.litterati.org/) - Photograph and tag litter, with open-data export.
- [SISS-Geo](https://www.biodiversidade.ciss.fiocruz.br/) - Fiocruz app for reporting sick or dead wild animals in Brazil.

## Learning

- [Project Pythia](https://projectpythia.org/) - Open tutorials on Python for the geosciences, plus domain [cookbooks](https://cookbooks.projectpythia.org/).
- [Xarray Tutorial](https://tutorial.xarray.dev/) - The official interactive xarray tutorial.
- [Pangeo Gallery](https://gallery.pangeo.io/) - Notebooks for scalable ocean and climate analysis.
- [OceanHackWeek](https://oceanhackweek.org/) - Annual workshop on ocean data science, with [tutorials](https://github.com/oceanhackweek/ohw-tutorials) on GitHub.
- [IOOS Code Lab](https://ioos.github.io/ioos_code_lab) - Notebooks for accessing ocean observing data.
- [Climatematch Academy](https://comptools.climatematch.io/) - Free course book for computational climate science (CC BY 4.0).
- [Earth and Environmental Data Science](https://earth-env-data-science.github.io/) - Open book on Python for Earth data.
- [Earth Lab](https://earthdatascience.org/) - Lessons on working with Earth and environmental data.

## Related lists

- [awesome-open-climate-science](https://github.com/pangeo-data/awesome-open-climate-science) - Open software for atmosphere, ocean and climate science.
- [awesome-ocean-data-resources](https://github.com/schmidtocean/awesome-ocean-data-resources) - Schmidt Ocean Institute's list of ocean data portals.
- [Awesome-Hydrospatial](https://github.com/monocilindro/Awesome-Hydrospatial) - Open software for ocean mapping and hydrography.
- [awesome-marine-hacking](https://github.com/hackforthesea/awesome-marine-hacking) - Datasets, hardware and blogs for ocean hackathons.
- [Awesome-AI-for-Atmosphere-and-Ocean](https://github.com/XiongWeiTHU/Awesome-AI-for-Atmosphere-and-Ocean) - Papers on AI in atmospheric science and oceanography.
- [awesome-weather-models](https://github.com/rebase-energy/awesome-weather-models) - Catalogue of AI weather models and whether their code and weights are open.
- [open-sustainable-technology](https://github.com/protontypes/open-sustainable-technology) - Open-source projects in climate, energy and biodiversity.
- [Awesome-Geospatial](https://github.com/sacridini/Awesome-Geospatial) - Geospatial analysis tools and data.
- [awesome-earthobservation-code](https://github.com/acgeospatial/awesome-earthobservation-code) - Earth observation tools and tutorials.

## Inbox

Instagram reels that inspired this list and still need transcribing (yt-dlp + Whisper, above).
Each becomes an entry in the matching section once its subject is known and checked.

| Reel | Note it came with |
|---|---|
| [DdXamf8jr9Y](https://www.instagram.com/reel/DdXamf8jr9Y/) | |
| [Dcbru9ZIF-B](https://www.instagram.com/reel/Dcbru9ZIF-B/) | |
| [DZmp_lZvXWQ](https://www.instagram.com/reel/DZmp_lZvXWQ/) | Reading Instagram from an agent |
| [Dc1EBSGR-Pm](https://www.instagram.com/reel/Dc1EBSGR-Pm/) | |
| [DdpPu_tKxcm](https://www.instagram.com/reel/DdpPu_tKxcm/) | Awesome ocean science |
| [DcCuu9Ny7IO](https://www.instagram.com/reel/DcCuu9Ny7IO/) | Speech to text |
| [DbSMFB1x0xm](https://www.instagram.com/reel/DbSMFB1x0xm/) | |
| [DcKOM_ojl4d](https://www.instagram.com/reel/DcKOM_ojl4d/) | |
| [Dd4Ffc2Cf9O](https://www.instagram.com/reel/Dd4Ffc2Cf9O/) | |
| [DdoUGFZRCDU](https://www.instagram.com/reel/DdoUGFZRCDU/) | AI wave models vs. WW3 |
| [DdjlgQTDSZ_](https://www.instagram.com/reel/DdjlgQTDSZ_/) | |

Still to check before adding: NEMO, CROCO, TELEMAC/TOMAWAC, Ocean Data View, Nautilus Live and
NOAA Ocean Exploration websites, Surfline, OpenSeaMap, Protected Planet, Marine Regions, Ocean
Health Index, Stewart's and Talley's physical oceanography textbooks.

## Contributing

Suggestions welcome: read [CONTRIBUTING.md](CONTRIBUTING.md), then open a pull request.

## License

[![CC0](https://licensebuttons.net/p/zero/1.0/88x31.png)](https://creativecommons.org/publicdomain/zero/1.0/)

The curation text of this list is released under [CC0 1.0](LICENSE). Linked projects keep their
own licences.
