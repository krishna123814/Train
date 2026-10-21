package com.example.train

import java.util.Random
import kotlin.math.*

/** Sab physics constants (SI units: meter, second). Ye file Android se bilkul independent hai. */
object Phys {
    const val G = 9.81
    const val VMAX = 56.0          // ~200 km/h hard limit
    const val TRAC = 1.1           // max acceleration (m/s^2) - low speed par
    const val POWER = 40.0         // W/kg  -> accel = POWER / v (high speed par)
    const val R0 = 0.012           // rolling resistance
    const val R1 = 0.00018
    const val R2 = 0.00023         // air drag
    const val BRAKE = 1.05         // service brake decel
    const val EMERG = 1.8          // emergency brake decel
    const val TRAIN_LEN = 85.0
    const val PLATFORM = 135.0
    const val ZONE = 30.0          // nose is zone me ruke to station sahi
    const val DWELL = 6.0          // station par rukne ka time (sec)
    const val PENALTY = 15.0       // station overrun penalty (sec)
}

enum class Route(val label: String, val stations: Int, val spacing: Double) {
    SHORT("Chhota", 4, 2800.0),
    MEDIUM("Medium", 6, 3000.0),
    LONG("Lamba", 9, 3400.0);

    fun km(): Double = (stations - 1) * spacing / 1000.0
}

enum class Level(val label: String, val skill: Double, val slack: Double) {
    EASY("Easy", 0.70, 16.0),
    MEDIUM("Medium", 0.85, 8.0),
    HARD("Hard", 1.00, 4.0)
}

enum class Phase { WAIT, RUN, DWELL, DONE }

class Station(val name: String, val mark: Double) {
    val p1 = mark + 6.0
    val p0 = p1 - Phys.PLATFORM
}

private val NAMES = listOf(
    "Ambala", "Kota", "Nagpur", "Bhopal", "Itarsi", "Jhansi", "Agra", "Mathura", "Vadodara",
    "Surat", "Pune", "Mysuru", "Raipur", "Gaya", "Patna", "Ranchi", "Lucknow", "Kanpur",
    "Ajmer", "Udaipur", "Rewa", "Satna", "Katni", "Warangal", "Vijayawada", "Guntur"
)

/** Track: stations + pahadi height-map. Slope stations ke aas-paas zero (flat) hoti hai. */
class Track(seed: Long, val route: Route) {
    val stations = ArrayList<Station>()
    val length: Double
    private val step = 5.0
    private val h: DoubleArray

    init {
        val rnd = Random(seed)
        val names = NAMES.shuffled(rnd)
        var x = 150.0
        for (i in 0 until route.stations) {
            stations.add(Station(names[i % names.size], x))
            if (i < route.stations - 1) x += route.spacing * (0.88 + rnd.nextDouble() * 0.26)
        }
        length = stations.last().mark + 1200.0

        val n = 5
        val amp = doubleArrayOf(0.030, 0.026, 0.020, 0.016, 0.010)
        val wl = DoubleArray(n) { 500.0 + rnd.nextDouble() * 1700.0 }
        val ph = DoubleArray(n) { rnd.nextDouble() * 2.0 * PI }

        fun slopeFn(px: Double): Double {
            var s = 0.0
            for (k in 0 until n) s += amp[k] * sin(2.0 * PI * px / wl[k] + ph[k])
            s = (s * 1.15).coerceIn(-0.075, 0.075)
            // station ke paas flat
            var dmin = 1e9
            for (st in stations) {
                val lo = st.mark - 340.0
                val hi = st.mark + 220.0
                val d = if (px < lo) lo - px else if (px > hi) px - hi else 0.0
                if (d < dmin) dmin = d
            }
            val t = (dmin / 420.0).coerceIn(0.0, 1.0)
            return s * (t * t * (3.0 - 2.0 * t))
        }

        val cnt = ceil((length + 800.0) / step).toInt() + 3
        h = DoubleArray(cnt)
        for (i in 0 until cnt - 1) {
            val a = slopeFn(i * step)
            val b = slopeFn((i + 1) * step)
            h[i + 1] = h[i] + 0.5 * (a + b) * step
        }
    }

    fun heightAt(x: Double): Double {
        val f = (x / step).coerceIn(0.0, (h.size - 1).toDouble() - 0.001)
        val i = f.toInt()
        val t = f - i
        return h[i] * (1.0 - t) + h[i + 1] * t
    }

    fun slopeAt(x: Double): Double = (heightAt(x + 4.0) - heightAt(x - 4.0)) / 8.0
}

class TrainSim(val track: Track, val name: String, val ai: Level? = null) {
    var x = 0.0
    var v = 0.0
    var throttleIn = 0.0
    var brakeIn = 0.0
    var tEff = 0.0
    var bEff = 0.0
    var phase = Phase.WAIT
    var next = 1
    var dwellLeft = 0.0
    var emergency = false
    var penalty = 0.0
    var clock = 0.0
    var finishTime = -1.0
    var topSpeed = 0.0
    var overruns = 0
    var msg = ""
    var msgT = 0.0

    private val rnd = Random(name.hashCode().toLong() * 31 + 7)
    private var aimFor = -1
    private var aimOff = 6.0

    init {
        x = track.stations[0].mark - 6.0
    }

    val finished: Boolean get() = phase == Phase.DONE

    fun go() {
        if (phase == Phase.WAIT) phase = Phase.RUN
    }

    fun say(s: String) {
        msg = s
        msgT = 3.4
    }

    fun grade(): Double = (track.heightAt(x) - track.heightAt(x - Phys.TRAIN_LEN)) / Phys.TRAIN_LEN

    fun distToMark(): Double = track.stations[min(next, track.stations.size - 1)].mark - x

    /** Abhi ki speed se rukne ke liye kitni decel chahiye / available decel. >1 matlab ab bhi nahi rukega */
    fun brakeRatio(): Double {
        val d = max(distToMark() - 8.0, 1.0)
        return (v * v / (2.0 * d)) / Phys.BRAKE
    }

    fun step(dt: Double) {
        if (msgT > 0) msgT -= dt
        if (phase == Phase.WAIT || phase == Phase.DONE) return
        clock += dt
        if (ai != null) aiThink(ai)

        var tIn = throttleIn
        var bIn = brakeIn
        if (phase == Phase.DWELL || emergency) {
            tIn = 0.0
            bIn = 1.0
        }
        tEff += (tIn - tEff).coerceIn(-3.0 * dt, 1.2 * dt)
        bEff += (bIn - bEff).coerceIn(-3.0 * dt, 2.0 * dt)
        if (bEff > 0.03) tEff = 0.0

        val trac = tEff * min(Phys.TRAC, Phys.POWER / max(v, 1.0))
        val res = if (v > 0.01) Phys.R0 + Phys.R1 * v + Phys.R2 * v * v else 0.0
        val grav = Phys.G * grade()
        val brk = bEff * (if (emergency) Phys.EMERG else Phys.BRAKE)
        var a = trac - res - grav
        if (v > 0.0) a -= brk
        v += a * dt
        if (v < 0.0) v = 0.0
        if (v > Phys.VMAX) v = Phys.VMAX
        x += v * dt
        if (v > topSpeed) topSpeed = v

        stationLogic(dt)
    }

    private fun stationLogic(dt: Double) {
        val st = track.stations[next]
        if (phase == Phase.RUN) {
            if (!emergency) {
                if (x > st.mark) {
                    emergency = true
                    penalty += Phys.PENALTY
                    overruns++
                    say("Station chhoot gaya! Emergency brake  +${Phys.PENALTY.toInt()}s")
                } else if (v < 0.25 && x >= st.mark - Phys.ZONE) {
                    v = 0.0
                    arrive(st)
                }
            } else if (v < 0.02) {
                emergency = false
                v = 0.0
                arrive(st)
            }
        } else if (phase == Phase.DWELL) {
            dwellLeft -= dt
            if (dwellLeft <= 0.0) {
                next++
                phase = Phase.RUN
                say("Gate band. Chalo!")
            }
        }
    }

    private fun arrive(st: Station) {
        if (next >= track.stations.size - 1) {
            phase = Phase.DONE
            finishTime = clock + penalty
            say("Manzil aa gayi: ${st.name}")
        } else {
            phase = Phase.DWELL
            dwellLeft = Phys.DWELL
            say("${st.name} station - gate khul gaye")
        }
    }

    /** Computer driver: speed-target controller */
    private fun aiThink(lv: Level) {
        if (phase != Phase.RUN || emergency) {
            throttleIn = 0.0
            brakeIn = 0.0
            return
        }
        val st = track.stations[next]
        if (aimFor != next) {
            aimFor = next
            aimOff = 5.0 + lv.slack * rnd.nextDouble()
        }
        val d = st.mark - aimOff - x
        val aPlan = Phys.BRAKE * (0.60 + 0.22 * lv.skill)
        val vDes = sqrt(2.0 * aPlan * max(d, 0.0))
        val vCruise = 52.0 * lv.skill
        val vTarget = min(vCruise, vDes)
        val approaching = vDes < vCruise
        if (v > vTarget + 0.3) {
            throttleIn = 0.0
            brakeIn = ((if (approaching) 0.6 else 0.0) + (v - vTarget - 0.3) * 0.25).coerceIn(0.0, 1.0)
        } else {
            brakeIn = 0.0
            throttleIn = ((vTarget - v) * 0.6).coerceIn(0.0, 1.0)
        }
        // safety: agar ab bhi pura brake na lagaya to station chhoot jayega
        val need = v * v / (2.0 * max(d + aimOff - 2.0, 0.5))
        if (d <= 0.0 || need > Phys.BRAKE * 0.88) {
            throttleIn = 0.0
            brakeIn = 1.0
        }
    }
}

class Race(val route: Route, val level: Level, seed: Long) {
    val track = Track(seed, route)
    val player = TrainSim(track, "Aap")
    val ai = TrainSim(track, "AI", level)
    var countdown = 4.0
    var started = false
    var sinceStart = 0.0
    var over = false
    var playerTime = 0.0
    var aiTime = 0.0
    var playerWon = false

    fun update(dt: Double) {
        if (over) return
        if (!started) {
            countdown -= dt
            if (countdown <= 0.0) {
                started = true
                player.go()
                ai.go()
            }
            return
        }
        sinceStart += dt
        player.step(dt)
        ai.step(dt)
        if (player.finished) {
            var guard = 0
            while (!ai.finished && guard < 200000) {
                ai.step(0.05)
                guard++
            }
            playerTime = player.finishTime
            aiTime = if (ai.finished) ai.finishTime else 1e9
            playerWon = playerTime <= aiTime
            over = true
        }
    }
}
