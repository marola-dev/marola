package marola.conditions

import kyo.*
import marola.http.Http
import marola.json.JsonValue
import marola.model.{BeachForecast, Beach, HourlyConditions}
import java.time.LocalDateTime

/**
 * Open-Meteo (open-meteo.com) — free, no API key for non-commercial use
 * (https://open-meteo.com/en/pricing: "Open-Meteo is free for non-commercial use"). Two endpoints
 * are combined and joined by matching hourly timestamp: the general Forecast API (air temp, wind,
 * UV, precipitation) and the Marine API (wave height, sea surface temperature, current). Both
 * return `time` as a *local, zone-less* string (e.g. "2026-09-05T14:00") when `timezone=auto` is
 * passed, alongside a separate `timezone` field naming the IANA zone — that zone is what
 * `Recommender` uses to figure out which hours count as "tomorrow" at that beach, not the caller's
 * own JVM default zone.
 */
object OpenMeteoClient:

  private val ForecastBase = "https://api.open-meteo.com/v1/forecast"
  private val MarineBase = "https://marine-api.open-meteo.com/v1/marine"

  def forecastFor(beach: Beach, forecastDays: Int = 2): BeachForecast < Sync =
    val coords = beach.coordinates
    val weatherUrl =
      s"$ForecastBase?latitude=${coords.lat}&longitude=${coords.lon}" +
        "&hourly=temperature_2m,wind_speed_10m,wind_direction_10m,uv_index,precipitation_probability,is_day" +
        s"&forecast_days=$forecastDays&timezone=auto"
    val marineUrl =
      s"$MarineBase?latitude=${coords.lat}&longitude=${coords.lon}" +
        "&hourly=wave_height,sea_surface_temperature,ocean_current_velocity" +
        // MIP-0001: period/swell for the detailed block, sea level (tides) for `Tides.extrema`.
        // Variable names confirmed live against the Marine API on 2026-09-05.
        ",wave_period,wave_direction,swell_wave_height,swell_wave_period,sea_level_height_msl" +
        s"&forecast_days=$forecastDays&timezone=auto"

    for
      weatherBody <- Http.getString(weatherUrl)
      marineBody <- Http.getString(marineUrl)
    yield merge(beach, JsonValue.parse(weatherBody), JsonValue.parse(marineBody))

  private def merge(beach: Beach, weather: JsonValue, marine: JsonValue): BeachForecast =
    def col(json: JsonValue, key: String): Vector[Option[Double]] =
      json("hourly")(key).arr.map(_.num)

    val times = weather("hourly")("time").arr.flatMap(_.str)
    val airTemp = col(weather, "temperature_2m")
    val windSpeed = col(weather, "wind_speed_10m")
    val windDirection = col(weather, "wind_direction_10m")
    val uvIndex = col(weather, "uv_index")
    val precipitation = col(weather, "precipitation_probability")
    val isDay = col(weather, "is_day") // Open-Meteo returns 0/1, not a JSON boolean

    val marineTimes = marine("hourly")("time").arr.flatMap(_.str)
    val waveHeight = col(marine, "wave_height")
    val seaTemp = col(marine, "sea_surface_temperature")
    val current = col(marine, "ocean_current_velocity")
    val wavePeriod = col(marine, "wave_period")
    val waveDirection = col(marine, "wave_direction")
    val swellHeight = col(marine, "swell_wave_height")
    val swellPeriod = col(marine, "swell_wave_period")
    val seaLevel = col(marine, "sea_level_height_msl")
    val marineIndexByTime = marineTimes.zipWithIndex.toMap

    def at(vec: Vector[Option[Double]], idx: Int): Option[Double] =
      if idx >= 0 && idx < vec.length then vec(idx) else None

    val hours = times.zipWithIndex.map {
      case (t, i) =>
        val mi = marineIndexByTime.get(t)
        HourlyConditions(
          time = LocalDateTime.parse(t),
          airTempC = at(airTemp, i),
          seaTempC = mi.flatMap(at(seaTemp, _)),
          waveHeightM = mi.flatMap(at(waveHeight, _)),
          windSpeedKmh = at(windSpeed, i),
          windDirectionDeg = at(windDirection, i),
          currentVelocityKmh = mi.flatMap(at(current, _)),
          uvIndex = at(uvIndex, i),
          precipitationProbabilityPct = at(precipitation, i),
          isDaylight = at(isDay, i).map(_ == 1.0),
          wavePeriodS = mi.flatMap(at(wavePeriod, _)),
          waveDirectionDeg = mi.flatMap(at(waveDirection, _)),
          swellWaveHeightM = mi.flatMap(at(swellHeight, _)),
          swellWavePeriodS = mi.flatMap(at(swellPeriod, _)),
          seaLevelM = mi.flatMap(at(seaLevel, _))
        )
    }.toList

    val timezoneId = weather("timezone").str.getOrElse("UTC")
    BeachForecast(beach, timezoneId, hours)
