# MIP-0052: Should marola ever run its own wave model, and in what language? — a costed survey

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude (Opus 5), for M. Hoffmann (request of 2026-09-09, attaching a prepared plan "Porting WAVEWATCH III to C++/Kokkos on a Single H100" and asking for other language options — Rust, Scala, Julia, FPGA, Chisel, Python, modern Fortran, CUDA, array-oriented — with tests on an RTX 4090 and access to an H100) |
| **Created** | 2026-09-10 |
| **Phase** | Not a marola phase. This is a survey with a verdict, in the shape `docs/4-Research-and-plans/AGENT-FRAMEWORKS-SURVEY.md` uses: what exists, what it would cost, and what marola should do — which is mostly "not this" |
| **Related** | MIP-0051 (the sibling, and the reason this question came up: §2's Jurerê case is what a nearshore grid would fix); MIP-0051 §5.3/§5.4 (the buoy ledger that gates any of this); `docs/4-Research-and-plans/AGENT-FRAMEWORKS-SURVEY.md` (the house convention this doc follows: a technology named here is a reference to read, not an adopted dependency) |
| **Effort** | XL, and out of marola's tree entirely — the attached plan estimates 7–12 months solo part-time with ~40% stall risk. This MIP's own effort is S: it is a survey, and its deliverable is a decision |
| **Gain** | `infra/dev-loop` — a costed answer to a question that will otherwise keep resurfacing, and a resolution threshold (§5) that says exactly when the answer changes |
| **Effort vs Gain** | **park**, with a named trigger. marola's wave problem is ~87 core-hours per 48-h cycle at 1.1 km (§4.1) — real, but tractable on a workstation without any port. The port becomes worth reconsidering only at 550 m, and only after MIP-0051 §5.4's ledger shows nearshore resolution is the binding error |
| **Depends on** | Nothing technically. Every rung of MIP-0051 stands without this. Do not read this MIP as a prerequisite for anything marola ships |
| **Blocked by** | none |
| **Risk** | That this MIP reads as a plan rather than a rejection, and someone starts a 12-month port for a beach forecast in Florianópolis. §4.1's arithmetic exists to prevent that. A narrower second risk is legal and applies **only to publishing a port**, not to running the model (§4.2) |
| **Cost so far** | — |

## 1. Summary

MIP-0051 found that public wave models disagree by up to 6.7× at marola's sheltered beaches because
they cannot resolve them. The obvious fix is to run a wave model ourselves at a resolution that can.
This MIP costs that idea before anyone builds it, and answers the language question that came with
it.

The answer is that marola should run stock WW3 in Fortran on CPUs, if it runs anything at all: a
1.1 km nest, the coarsest that resolves the bay in MIP-0051 §2, costs about **87 core-hours per
forecast cycle**, which is 2.7 hours on 32 cores and needs no port, no GPU and no new language.
Only a 550 m nest, 8× more expensive again, would make the question live.

Three findings from the review matter more than the language comparison itself. **The DOE Kokkos
kernels the attached plan is built around do not exist**; the repository was enumerated. **WW3's
licence is not open source**, and while it plainly permits running the model and publishing its
output, it restricts *redistributing the code*, which constrains publishing a port but nothing
marola would actually ship. And **an equivalent spectral wave model already reached 37× on GPUs
without leaving Fortran**, while a partial offload of WW3's own hotspot reached 1.4× per node,
so residency, not language, is what decides the outcome.

## 2. Motivation

The request arrived with a prepared 12-month plan to port WAVEWATCH III's compute core to C++/Kokkos
for an H100, and asked which other languages might be better. Both halves deserve an answer, but the
prior question was never asked: **how much compute does marola's actual wave problem need?** A port
is a means. Nobody had costed the end.

Four premises in the request also need testing before any language is discussed, and **three of them
did not survive checking**:

1. *That DOE has WW3 source terms in C++/Kokkos to adopt.* **False**: §4.2. The repository was
   enumerated; there are none, and the attached plan's Route B' and Task 0 depend on them.
2. *That WRF and WW3 are the same kind of problem, both GPU-accelerable.* **False**: §4.3. WRF has
   no officially supported GPU path; MPAS is the model in that family that does.
3. *That the choice of target language is what decides the outcome.* **Not supported**: §4.2.3. The
   published evidence says residency decides it: an equivalent spectral wave model reached 37× on
   GPUs **without leaving Fortran**, while a language-agnostic partial offload of WW3's own hotspot
   reached 1.4× per node.
4. *That a GPU helps at marola's scale.* **Not at 1.1 km**: §4.1.

A fifth issue nobody raised is legal rather than technical, and may matter more than all of them:
WW3's licence forbids disclosing the software to third parties and requires modifications be offered
back to NOAA (§4.2).

## 3. User-visible change

**None.** Nothing in this MIP ships. If its conditional recommendation (§5) were ever adopted, the
user-visible change would be MIP-0051 §5.3's, not this one's.

## 4. Data sources and dependencies reviewed

### 4.1 The size of marola's actual problem — the number this MIP exists to establish

Cost scales as (sea points) × (time steps), and the CFL condition ties the time step to the grid
spacing, so **halving the grid costs 8×**: 4× the points and 2× the steps. That ratio is pure
geometry and holds whatever a single point-update actually costs.

Anchoring the magnitude to a published figure (a WW3 hindcast at global 0.5° costs roughly
17,800 core-hours per simulated year, corroborated by a separate figure of 100–500 cores for
0.5–2 hours per 7-day global forecast; both taken from a search-result summary and **not fetched
from the paper**, see "Not checked"), a domain covering Florianópolis and its approaches
(27.2–28.2 S, 48.2–48.8 W) costs, per 48-hour forecast cycle:

| Nest | Sea points | Cost vs one global 0.5° run | Core-hours / cycle | On 32 cores |
|---|---|---|---|---|
| 2.2 km | ~800 | 0.11× | ~11 | 20 min |
| **1.1 km** | ~3,300 | 0.89× | **~87** | **2.7 h** |
| 550 m | ~13,200 | 7.1× | ~693 | 22 h |

**Jurerê bay, MIP-0051's worst case, is about 3 km wide.** At 2.2 km it spans one or two cells,
which is not a resolved bay. At 1.1 km it spans three, which is the coarsest grid that answers the
question that motivated all of this. So 1.1 km is the target, and 1.1 km is ~2.7 hours on 32 CPU
cores per cycle: **demanding but entirely feasible on one workstation, with no port, no GPU and no
new language.** Twice-daily fits in a day with room to spare.

The memory footprint is negligible at every resolution: 3,300 sea points × 1,152 spectral bins in
FP32 is about 15 MB, against the 4.6 GB a global 0.25° spectrum needs. marola's problem is
compute-bound in wall-clock terms and nowhere near any memory limit, which matters for §4.2: the
attached plan's central engineering concern, keeping a multi-gigabyte state device-resident, is
not marola's concern at all.

### 4.2 Two findings that change the attached plan before any language is chosen

**The DOE Kokkos kernels do not exist.** The attached plan's Task 0, "the first action of this
project is to contact that team", with Route B' promising to "subtract 2–4 months", rests on the
claim that E3SM/Omega is rebuilding WW3 in C++/Kokkos. Checked directly against the repository trees
on 2026-09-10: `E3SM-Project/Omega` is public, its C++/Kokkos code under `components/omega/` is an
**ocean circulation model** (the MPAS-Ocean successor), and a recursive enumeration of its 10,445
paths finds **no wave source terms of any kind**. E3SM does carry WW3, as a **Fortran git
submodule** (`components/ww3/src/WW3` → `E3SM-Project/WW3`, branch `e3sm`). A GitHub-wide code search
for `wavewatch kokkos` returns **zero results**. The E3SM wave group's actual trajectory went
OpenACC (2023) → **ML emulation** (2026, §4.2.6), never Kokkos. Route B' should be struck.

**WAVEWATCH III's licence is not open source, but it restricts less than it first appears, and the
distinction decides which parts of this MIP are affected.** The repository clones anonymously with no
registration, and downloading makes you a Licensee; there is no approval step to request. The full
text of `LICENSE.md` ("WAVEWATCH III® Software License", © 2009 National Weather Service) was read,
not summarised. At a glance:

| | Allowed? |
|---|---|
| Download, build and run WW3 for wave forecasting | **Yes** — the licence's central grant |
| Ask NOAA for permission first | **Not required** — downloading makes you a Licensee |
| Keep a **private** fork with your own modifications | **Yes**, with notices kept and changes declared |
| Publish the model's **output** (wave heights, a forecast, the marola map) | **Yes** — the licence governs software, not forecasts |
| Use it commercially | **Yes** — there is no non-commercial clause |
| Publish the **source**, or a derived port, in a **public** repository | **No**, on a literal reading — this is the one real restriction |
| Change the model's numerics or physics and keep the changes to yourself | **No** — you must *offer* them to NOAA (offer, not have accepted) |
| Call your own product "WAVEWATCH III" | **No** — it is a trademark |

In detail, what it **permits outright**:

- **Use**: "The Licensee may use the software **for any purpose relating to sea state prediction**."
  Running a nest for marola is the central case, not a loophole. There is **no non-commercial
  clause**, unlike MIP-0050's CC-BY-NC problem.
- **Private copies and private forks**: "A Licensee may reproduce sufficient software to satisfy its
  needs", provided copies keep the name, version and notices, and modifications are declared as such.
- **Publishing the model's output.** The licence governs software, not forecasts. Nothing restricts
  publishing wave heights on marola.dev.

What it **restricts**:

- **Redistribution**: "no disclosure of any portion of the software, whether by means of a media or
  verbally, may be made to any third party". On a literal reading this bars publishing WW3 source, or
  a derived port, to a public repository.
- **Modifications to the model proper**: a licensee who changes "numerical and physical approaches
  to wave modeling" **"is required to offer same to NOAA"** (to offer; NOAA need not accept).
- **Per-person acknowledgement**: everyone with access must state **in writing** that they have read
  and accepted the licence. Trivial for one developer; a real obligation if contributors ever touch
  WW3 code.
- **The trademark**: WAVEWATCH III® may not be used as a product name without permission.

The licence dates from 2009, before the code was on GitHub, when distribution meant requesting a
tarball from NOAA; the no-disclosure clause reads as "do not redistribute — send people to us", not
as secrecy. NOAA has since published the code itself, which sits in obvious tension with that clause,
but the tension is NOAA's to resolve rather than a licensee's.

Three questions follow naturally from reading the table, and are worth answering here because they
are the ones anyone re-reading this MIP will ask.

**"Can't I just keep a private fork?"** Yes, and this is the licence's own language, not a
workaround: "A Licensee may reproduce sufficient software to satisfy its needs." The restriction is
on making a fork *public*, not on having one.

**"Do I have to ask permission for any use at all?"** No. There is no registration, no application
and no approval step. The licence is already the grant, and cloning the repository makes you a
Licensee bound by it. This matters because "the licence is restrictive" is easily misread as "the
project needs NOAA's sign-off before it can start". It does not.

**"Then why is the code public?"** Because the licence is from **2009** and the distribution model
changed underneath it. It was written when obtaining WW3 meant asking NOAA for a tarball, and the
no-disclosure clause served to keep NOAA the single source rather than to keep the code secret. NOAA
itself later published everything on GitHub, which means NOAA disclosed it to the world, so the
clause no longer protects what it was written to protect. It is a licence that aged without being
rewritten, not a trap. That reading is inference from the dates and the clause's wording, however,
and it is not NOAA speaking; §11 keeps the question open rather than treating this paragraph as an
answer.

**The practical consequence is narrow.** Everything MIP-0051 §5.3 would do (run the model, publish
the forecast) is squarely permitted. Only publishing a *port* is constrained, and that is precisely
the thing §5 already recommends against on cost grounds. **Whether NOAA/EMC would permit a public
port is an open question (§11), not a blocker for anything marola would ship.** For contrast: Kokkos
is Apache-2.0, and Oceananigans.jl, a from-scratch GPU ocean model, is MIT.

### 4.2.1 The candidates, compared

| Target | GPU story | CPU reference from one source | Earth-system precedent | Verdict here |
|---|---|---|---|---|
| **Modern Fortran** (`do concurrent`, OpenACC) | nvfortran `-stdpar`; all three vendors offload pure `do concurrent` as of Aug 2026, and on NVIDIA it is *slightly faster* than OpenMP-target | **Best of any option** — compile without `-stdpar` and it *is* the original code; no second implementation | **WAM6-GPU: a spectral wave model, fully GPU, 37× on 8 A100s — in Fortran**; WW3's own OpenACC work; ICON operational on GPU at MeteoSwiss | **Recommended if speed is the goal** |
| **C++/Kokkos** | First-class CUDA; RTX 4090 (`ADA89`/`sm_89`) and H100 (`sm_90`) both explicitly supported in `cmake/kokkos_arch.cmake` | Yes, and proven: FESOM2's Kokkos port is **bit-for-bit with its C reference** on the Serial backend | Strongest general precedent: E3SM Omega, EAMxx/SCREAM, FESOM2-Kokkos | Viable; the attached plan's method is sound even though its premise was not |
| **Julia** | CUDA.jl is the most mature part of Julia's GPU stack | Yes — KernelAbstractions.jl compiles one `@kernel` to CUDA/ROCm/oneAPI/Metal **and CPU** | **Oceananigans.jl** (MIT, active today, 488 m global ocean on 768 A100s) — the first ocean model written *for* GPUs | Best language, **no wave physics** — you would rewrite WW3 from scratch |
| **Python/JAX** | XLA; strong multi-GPU | Yes; Veros's test suite checks every backend against its Fortran reference | **Veros** (1:1 translation of PyOM2's Fortran); **FESOM2-JAX** by the *same group* as the Kokkos port | Credible; bit-for-bit is hard (XLA fusion, TF32) |
| **ML surrogate** | Inference-shaped; suits a consumer GPU | N/A — it replaces the physics | **NLML** emulates WW3's exact nonlinear interactions: **136× faster than WRT, 1.04× DIA's cost, 2× DIA's accuracy**, stable over a year-long run | The sleeper option, and a scientific decision, not an engineering one |
| **Rust** | NVIDIA's CUDA Rust announced **2026-09-08**: `cuda-oxide` is "early alpha" on a pinned nightly; `cutile-rs` is on stable | Plausible, no established pattern | **None found.** No climate, weather or ocean model in Rust | Reject — you would be first, on a toolchain NVIDIA says it is maturing "into 2027 and beyond" |
| **Scala / Chisel** | **None.** Scala Native has no GPU backend; TornadoVM accepts **Java, not Scala**; Compute.scala last pushed 2024-08-19 | Irrelevant — there is no GPU build to compare against | None | **Reject.** See below |
| **Array DSLs** (GT4Py, Futhark, Halide, Dex) | GT4Py→CUDA is real: ICON4Py's Python dycore is **20–30% faster than Fortran+OpenACC** on GH200 | Futhark and GT4Py both emit CPU backends | GT4Py at CSCS/ECMWF — **but MeteoSwiss's *operational* GPU ICON uses OpenACC, not the DSL** | GT4Py is a **structured-grid stencil** DSL; `W3SRCEMD` is per-point spectral physics, not a stencil. Wrong shape |
| **FPGA** | Weather-on-FPGA is isolated COSMO stencils at **1.4–3.2× over KNL/P100** — a margin a 4090 erases | N/A | **No spectral wave or weather model runs on FPGA in production** | Reject |

### 4.2.2 On Scala specifically, since marola is a Scala repo

The tempting answer is wrong and should be said plainly. Scala Native targets LLVM for CPU and has
**no GPU backend**. TornadoVM, the one JVM framework that JITs bytecode to PTX, **supports Java and
not Scala** (its FAQ is explicit; Kotlin support has been an open issue since 2020), and it targets
data-parallel array code, not a five-dimensional spectral solver. `Compute.scala` is dormant. Chisel
is a hardware description language: it emits Verilog, which is §4.2.1's FPGA row, not a GPU path.

The one defensible Scala thread is an **integration** decision rather than a port target: a CUDA or
Kokkos kernel core, bound through the JVM's Panama FFM API (standard since JDK 22; marola is already
on JDK 25), driven by Scala. That moves the boundary; it does not move the compute core. Writing
spectral wave physics in Scala would mean inventing a GPU story no one in numerical computing uses.

### 4.2.3 The number that should decide this

WW3's source terms are **82% of runtime**. ORNL moved exactly those to GPU and measured
**4.7–6.6× per MPI rank against a single CPU core, but only 1.36–1.41× on a fair whole-node
comparison** (42 CPU cores and 6 V100s on Summit). Meanwhile **WAM6-GPU achieved 37× on 8 A100s for
an equivalent spectral wave model without leaving Fortran**, by refactoring layout and keeping the
whole step on the device.

The lesson is not "Kokkos versus Rust versus Julia". It is that **the speedup came from making the
state device-resident, and the language was never the binding constraint.** A port that changes
language without changing residency reproduces ORNL's 1.4×.

### 4.3 WRF, and why the GPU premise does not transfer

The request pairs WRF with high-performance GPU languages. NCAR's own support forum states that GPU
work in WRF is "decentralized and difficult to track down, and is not officially supported, unlike
MPAS which does have official GPU support built in." WRF on the described hardware would run on the
host's **CPU** cores. If atmospheric downscaling is ever wanted, **MPAS** is the model in that family
with an official GPU path, and it is the same E3SM lineage as the Omega work the attached plan
builds on, which makes it the more coherent choice on every axis, not just this one.

### 4.4 ECMWF AIFS — the option that actually suits the hardware

Open weights under CC BY 4.0, operational at ECMWF since 25 February 2025, roughly 2.5 minutes for a
10-day global forecast on a single A100. It is the only thing surveyed here that turns a consumer GPU
into a forecast. It is also ~0.25°, so it is a cheap *driver* for a nearshore wave nest and not a
substitute for one. If marola ever runs its own wave model, AIFS is a plausible source of boundary
winds alongside NOAA's free GFS.

## 5. Design

There is no code in this MIP. The design is a decision rule, and a threshold that says when to
revisit it.

**Recommendation: park the port; run nothing yet; measure one thing.**

1. **Do not port anything.** §4.1 shows marola's target resolution (1.1 km, the coarsest grid that
   resolves Jurerê) costs ~87 core-hours per cycle: a workstation job. The published evidence on
   porting WW3 is also discouraging at any scale: ORNL moved the source terms, which are 82% of
   runtime, onto GPUs and measured **1.36–1.41× per node** against 42 CPU cores. A 12-month project
   for 1.4× is a bad trade even for the people who need it; for marola it is not a trade at all.
2. **If MIP-0051 §5.4's ledger says nearshore resolution is the binding error**, run stock WW3 in
   Fortran, on CPUs, at 1.1 km, on the existing machine. No new language, no port, no GPU.
   The licence permits this without asking anyone (§4.2); a private fork is permitted too.
3. **Revisit this MIP only if 1.1 km proves insufficient**: that is, if the buoy scores and the
   physics say 550 m is needed. At 550 m the cost is ~693 core-hours per cycle (§4.1), which one
   workstation cannot do twice a day, and a GPU becomes a genuine question rather than an
   aspiration. That is the trigger, and it is the only one.

**If the trigger ever fires, the order of attempts is:** (a) tune and parallelise the stock Fortran
first, the attached plan's own Route 0, and the cheapest by a wide margin; (b) reduce the domain or
the spectral resolution, which is free and is what operational centres actually do; (c) GPU-offload
**in Fortran**, via `do concurrent` or OpenACC (this is what WAM6-GPU did to reach 37× on an
equivalent spectral wave model, and it keeps the CPU reference and the port in one source tree);
(d) only if that fails, a language port, and then Kokkos on the attached plan's method.

Route B' of the attached plan, adopting DOE's Kokkos WW3 kernels, must be struck outright: §4.2
establishes those kernels do not exist. Any schedule that subtracts months for them is subtracting
from nothing.

**On the hardware in the request:** the RTX 4090's FP64 throughput is 1/64 of its FP32, which sounds
disqualifying and is not: WW3 is single-precision by default, so the 4090 is a reasonable
development target. The binding constraint is not precision or memory (§4.1: ~15 MB), it is that
the work is not there to be accelerated at marola's scale.

## 6. Scoring / safety impact

**None.** Nothing here reaches `Swimability` or any user-facing output. If MIP-0051 §5.3 is ever
adopted, its own scoring impact is assessed there, under MIP-0051 §6's rule that model disagreement
moves the confidence text and never the score.

## 7. Verification plan

This MIP proposes no code, so there is nothing to unit-test. What it asserts is a cost model, and
the honest verification of a cost model is to measure it:

- **The one experiment worth running** (a day, not a quarter): build stock WW3 from source, run one
  of its own regtests, then run a 2.2 km Florianópolis nest with GFS boundary conditions and
  **time it**. That single number replaces every estimate in §4.1 and settles the resolution
  question empirically. It requires no port and no GPU.
- Done looks like: a measured core-hours figure for one cycle at 2.2 km, extrapolated by the 8×
  rule to 1.1 km and 550 m, recorded in `docs/benchmarks/` the way `just benchmark` runs are.

## 8. Risks, limitations, and honest caveats

- **§4.1's magnitude anchor is unverified, and two of its inputs are assumptions.** The 17,800
  core-hours figure came from a search summary, not from the source. The domain's sea fraction is
  assumed at 55% and is probably higher (the box extends east into open ocean), which would raise
  every core-hour figure proportionally. The spectral grid is assumed at 36×32 bins; the ORNL study
  used 36×50, which is 56% more work per point. The *ratios between resolutions* are sound
  arithmetic and survive all three; the absolute core-hours could be off by a factor of two or more,
  and in the expensive direction. §7's experiment exists because of this, and no decision should
  rest on the absolute number until it is measured.
- **Boundary conditions are not free of effort even when free of cost.** A nested run needs spectral
  boundary data from a global run; obtaining, storing and ingesting it is real work not costed here.
- **A nearshore wave nest does not fix wind.** MIP-0051 §2's Jurerê case is partly a sheltering
  problem in the wave field and partly a local wind problem. A wave nest driven by 25 km winds
  inherits the coarse wind. This bounds how much a wave-only nest can deliver, and is the strongest
  argument for eventually pairing it with MPAS, and the strongest argument for measuring first.
- **A port is not a speedup on its own.** The comparable projects in the attached plan report first
  GPU runs *slower* than the CPU baseline until the whole state stayed on the device. The
  attached plan's own estimate is 9–14 months before beating the current CPU code.
- **Nothing here is a marola dependency.** Written down so a future reader does not mistake a survey
  for a roadmap.

## 9. Alternatives considered

- **Do nothing at all, don't write this MIP.** Rejected: the question came with a 12-month plan
  attached and will return. A costed "no", with a named trigger for "yes", is worth more than
  silence.
- **Adopt the attached C++/Kokkos plan as written.** Rejected on §4.1 and §5: it is a good plan for
  an operational centre running basin-scale domains many times a day, and marola is not that. Its
  own estimate is 7–12 months with ~40% stall risk, against a workload that fits on a workstation.
- **Port to one of the other candidate languages instead** (§4.2). Rejected for the same reason the
  Kokkos plan is: the choice of target language is a question about *how* to port, and this MIP's
  answer is that marola should not port. The survey is kept because it was asked for, and because it
  is the right reference if the §5 trigger ever fires.
- **Use MPAS for atmosphere instead of WRF.** Not rejected, deferred. §4.3 establishes MPAS is the
  model with an official GPU path, so if downscaling is ever wanted it is the right starting point.
  It is a different proposal and needs its own number.
- **Buy the data instead of computing it.** Not seriously costed here, and it deserves to be: a
  commercial nearshore forecast for one stretch of coast may be cheaper than any of this. It is the
  honest alternative to every option above, and it belongs in whatever MIP proposes the nest.
- **AI surrogates for the source terms.** Not rejected, deferred, and more seriously than the
  attached plan treats it. **NLML** (Ikuyajolu et al., *JGR: Machine Learning and Computation*, 2026,
  the same authors as the 2023 OpenACC WW3 paper) emulates WW3's exact nonlinear interactions at
  **136× the speed of WRT, 1.04× the cost of DIA and twice DIA's accuracy**, stable through a
  year-long integration. That is the direction the E3SM wave group actually went after OpenACC. It
  is out of scope here because it replaces physics rather than accelerating it, a scientific
  decision, not an engineering one, and because marola has no baseline to judge it against. It
  deserves its own MIP if a nest is ever built.

## 11. Open questions

- **What does a 2.2 km nest actually cost on the target machine?** §7's experiment. Everything in
  §4.1 is an estimate until this is run, and it is cheap.
- **How many cores does the always-on box have?** §4.1's verdict is stated in core-hours because
  this was never established. At 32 cores a 1.1 km nest is comfortable; at 8 it is not.
- **Does MIP-0051 §5.4's ledger show nearshore resolution is the binding error?** If the buoy scores
  say the public models are already close at our beaches, this entire MIP is moot and should be
  marked Rejected rather than parked.
- **May a public GPU port of WW3 be published at all?** §4.2's licence reading says no on a literal
  construction, and NOAA publishes the code itself, so the answer is genuinely unclear and only
  NOAA/EMC can settle it. Scope, stated precisely so this is not over-read: it does **not** affect
  running stock WW3, keeping a private fork, or publishing forecasts, all of which the licence
  permits. It affects only options ending in a **publicly redistributed derivative**.
- **Follow-up MIP:** if a nearshore nest is ever adopted, the "home machine computes, pushes an
  artefact, static site consumes" deployment pattern needs designing once for the repo; it would
  also serve MIP-0048's training runs. MIP-0051 §11 raises the same point; it needs one number, not
  two.

## Appendix

### Checked live

All 2026-09-10, by clone-and-grep or direct API/page fetch, not from search summaries unless said.

- **`NOAA-EMC/WW3` clones anonymously**, no registration. `develop` is **VERSION 7.14**. Grepping the
  whole tree for `!$acc`, `omp target` and `do concurrent` returns **zero hits**; the only OpenMP is
  the CPU-side `OMPG`/`OMPH` switches. `git ls-remote --heads` returns exactly **11 branches**, none
  GPU-related. `E3SM-Project/WW3` branch `bluepulse` likewise has zero hits for the same patterns.
- **WW3 licence** (https://raw.githubusercontent.com/NOAA-EMC/WW3/main/LICENSE.md, "WAVEWATCH III®
  Software License", © 2009 National Weather Service): the four clauses quoted in §4.2 are verbatim:
  no third-party disclosure; modifications must be offered to NOAA; per-employee written
  acknowledgement; use "for any purpose relating to sea state prediction"; WAVEWATCH III® is a
  trademark. Not OSI-approved.
- **WW3 is FP32 by default**: `model/src/w3wdatmd.F90:491` allocates `VA(NSPEC,0:NSEALM)`, declared
  `REAL, POINTER :: VA(:,:)` at line 150: default real unless built `-r8`.
- **E3SM Omega contains no WW3 source terms.** `E3SM-Project/Omega` recursive tree enumerated
  (10,445 paths, not truncated); searching `wave|ww3|wavewatch|spect|source_?term` over
  `components/omega/` finds none. `.gitmodules` on `Omega@develop` carries
  `components/ww3/src/WW3` → `git@github.com:E3SM-Project/WW3.git`, branch `e3sm`, a Fortran
  gitlink. GitHub code search `wavewatch kokkos` → **`"total_count": 0`**.
- **Ikuyajolu et al. 2023** (https://gmd.copernicus.org/articles/16/1445/2023/, fetched): WW3 v6.07,
  unstructured 59K/228K-node meshes, **36 directions × 50 frequencies**, ST4/DB1/BT1/NL1;
  `W3SRCEMD` is **~82% of execution time**; **2.3–2.4× (P100), 4.7–6.6× (V100) per rank vs one CPU
  core**; **whole-node 1.36–1.41×** (42 cores vs 6 V100, 7 ranks/GPU). Code frozen on Zenodo
  (doi 10.5281/zenodo.6483401, CC-BY-4.0), **not a branch**.
- **WAM6-GPU v1.0** (https://gmd.copernicus.org/articles/17/6123/2024/): a spectral wave model fully
  GPU-accelerated **in Fortran + OpenACC**: 37× on 8 A100s vs a dual-socket Xeon 6236 node; global
  1/10° (2,923,286 points) 7-day run cut from >2 h to **7.6 min**.
- **`do concurrent` on GPUs II**, arXiv:2608.20586 (20 Aug 2026, authors include NVIDIA and E3SM/Omega
  staff): all three vendors offload pure `do concurrent`; on NVIDIA it is **slightly faster than the
  OpenMP-target version**; Fortran 2023's `reduce` clause works. Authors still advise keeping data
  directives in production.
- **NLML** (https://agupubs.onlinelibrary.wiley.com/doi/10.1029/2025JH000699, Ikuyajolu, Van Roekel,
  Brus & Thomas, 2026): NN emulator of WW3's exact nonlinear interactions: **136× faster than WRT,
  1.04× DIA's cost, ~2× DIA's accuracy**, stable through a year-long standalone integration.
- **Kokkos**: current release **5.2.1** (2026-08-17); **5.0+ requires C++20**; nvcc ≥ 12.2;
  `cmake/kokkos_arch.cmake` on `develop` carries `ADA89`→`sm_89` (RTX 4090) and `HOPPER90`→`sm_90`
  (H100); Apache-2.0 with LLVM exception. The `master` README still advertising 4.7.01/C++17 is stale.
- **FESOM2 Kokkos port**: arXiv:2606.11356 (submitted 9 Jun 2026) confirmed; ~74k lines of core
  Fortran; five-year-mean **SST RMS difference 0.006 °C**; Kokkos **bit-for-bit with the C reference
  on CPU**; **1.6–3.7× A100 node vs CPU node**; github.com/koldunovn/fesom_kokkos is real and public
  (~26k LOC, Kokkos 4.4.01 vendored).
- **Oceananigans.jl**: MIT, 1,413 stars, **v0.112.0 (2026-09-08)**, pushed 2026-09-10; 488 m global
  ocean on 768 A100s (arXiv:2309.06662); portability via KernelAbstractions.jl (CUDA/ROCm/oneAPI/
  Metal/CPU from one `@kernel`).
- **NVIDIA CUDA Rust**, announced **2026-09-08**: `cuda-oxide` is NVIDIA's own "early alpha", pinned
  nightly-2026-04-03; `cutile-rs` on crates.io, stable Rust 1.89+. NVIDIA commits to maturing CUDA
  Rust "into 2027 and beyond". Rust-CUDA's README: "still in early development… Expect bugs, safety
  issues, and things that don't work."
- **TornadoVM supports Java, not Scala** (its FAQ; Kotlin an open issue since 2020). Scala Native has
  no GPU backend. `Compute.scala` last pushed **2024-08-19**.
- **ICON4Py/GT4Py**, arXiv:2608.21150: Python dycore **20–30% faster than Fortran+OpenACC** on GH200.
  But MeteoSwiss's **operational** GPU ICON uses OpenACC directives, not the DSL
  (https://gmd.copernicus.org/articles/19/755/2026/).
- **FPGA**: no spectral wave or weather model in production; COSMO stencil work reports **1.4–1.5×
  (vadvc)** and **2.1–3.2× (hdiff)** over KNL/P100.
- **RTX 4090** (NVIDIA product page + Ada whitepaper V2.02): 83 TFLOPS FP32, **FP64 = 1/64 of FP32**
  (288 FP64 cores on AD102), 24 GB GDDR6X, 384-bit, 450 W.
- **NCAR WRF/MPAS forum** (https://forum.mmm.ucar.edu/threads/gpu-support-for-wrf-mpas.12381/): WRF
  GPU support is "decentralized… not officially supported, unlike MPAS".
- **ECMWF AIFS**: weights CC BY 4.0 (https://huggingface.co/ecmwf/aifs-single-1.0); operational
  25 Feb 2025; ~2.5 min for a 10-day forecast on one A100.

### Not checked

- **§4.1's magnitude anchor.** The ~17,800 core-hours per hindcast year at global 0.5°, and the
  corroborating "100–500 cores for 0.5–2 h per 7-day forecast", both come from a **search-result
  summary and were never fetched from a source**. Two further inputs are assumptions: the domain's
  sea fraction (55%, probably low) and the spectral grid (36×32 bins; ORNL used 36×50, which is 56%
  more work per point). The resolution *ratios* are arithmetic and hold; the absolute core-hours do
  not, and §7 exists to replace them with a measurement. **No WW3 run of any kind was performed.**
- Whether NOAA/EMC would in practice permit publishing a GPU port. The licence text alone does not
  settle it; §11 raises it as a question rather than assuming either answer.
- `fesom_kokkos`'s licence (GitHub reports none) and its last-commit date; whether its bit-for-bit
  CPU claim holds under `-O3`/vectorisation or only at one optimisation level.
- Whether FESOM2-JAX's code is public.
- CUDA.jl's compile latency (TTFX), determinism guarantees and CPU-fallback semantics: the JuliaGPU
  2026 ecosystem review explicitly does not address these. CUDA.jl's and ClimaOcean.jl's exact
  licences (GitHub reports `NOASSERTION`).
- Whether `cutile-rs`/`cuda-oxide` produce reproducible FP32 results, or support FP64 at all.
- Numba and CuPy precedent in production Earth-system models: **not found**, which is not the same as
  does not exist; the search was not exhaustive.
- Halide's and Futhark's current release versions; Halide's scientific production users.
- WW3's full GPU working set beyond the spectral state arrays (halos, propagation scratch, per-thread
  temporaries, I/O buffers).
