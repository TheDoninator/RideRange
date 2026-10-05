"""Generates app/src/main/assets/regulations.json (the Rules tab dataset).

Run: python tools/gen_regulations.py   (then tools/check_links.py to verify every source URL)

Utah (state + cities) was checked against the statute text on le.utah.gov on the review date.
Other states are short summaries written from the statutes linked as sources; entries marked
"summary" should be read as "check the linked source for the exact wording".
"""
import json
import os

REVIEWED = "2026-10-04"
NCSL = {"title": "NCSL: State electric bicycle laws (overview)", "url": "https://www.ncsl.org/transportation/state-electric-bicycle-laws-a-legislative-primer"}

LIGHTS_GENERIC = "At night: a white front light and a red rear reflector or light (the usual bicycle equipment rule)."
SCOOTER_GENERIC_WHERE = ("No detailed scooter summary for this state yet. Stand-up e-scooters are usually treated like "
                         "bicycles or like mopeds, and cities often add their own rules. Check the state code linked below.")
EBIKE_GENERIC = "Check the state code for how e-bikes are defined (most states use Classes 1-3: 20 mph assist, 20 mph throttle, 28 mph assist)."

LEG = {
    "AL": ("Code of Alabama", "https://alison.legislature.state.al.us/code-of-alabama"),
    "AK": ("Alaska Statutes", "https://www.akleg.gov/basis/statutes.asp"),
    "AZ": ("Arizona Revised Statutes, Title 28", "https://www.azleg.gov/arstitle/"),
    "AR": ("Arkansas General Assembly", "https://www.arkleg.state.ar.us/"),
    "CA": ("California Codes", "https://leginfo.legislature.ca.gov/faces/codes.xhtml"),
    "CO": ("Colorado Revised Statutes", "https://leg.colorado.gov/colorado-revised-statutes"),
    "CT": ("Connecticut General Statutes", "https://www.cga.ct.gov/current/pub/titles.htm"),
    "DE": ("Delaware Code", "https://delcode.delaware.gov/"),
    "DC": ("Code of the District of Columbia", "https://code.dccouncil.gov/"),
    "FL": ("Florida Statutes", "https://www.flsenate.gov/Laws/Statutes"),
    "GA": ("Georgia General Assembly", "https://www.legis.ga.gov/"),
    "HI": ("Hawaii Revised Statutes", "https://www.capitol.hawaii.gov/hrscurrent/"),
    "ID": ("Idaho Statutes", "https://legislature.idaho.gov/statutesrules/idstat/"),
    "IL": ("Illinois Compiled Statutes", "https://www.ilga.gov/"),
    "IN": ("Indiana Code", "https://iga.in.gov/laws"),
    "IA": ("Iowa Code", "https://www.legis.iowa.gov/law/iowaCode"),
    "KS": ("Kansas Statutes", "https://ksrevisor.gov/"),
    "KY": ("Kentucky Revised Statutes", "https://apps.legislature.ky.gov/law/statutes/"),
    "LA": ("Louisiana Laws", "https://legis.la.gov/legis/LawSearch.aspx"),
    "ME": ("Maine Revised Statutes", "https://legislature.maine.gov/statutes/"),
    "MD": ("Maryland Statutes", "https://mgaleg.maryland.gov/mgawebsite/Laws/Statutes"),
    "MA": ("Massachusetts General Laws", "https://malegislature.gov/Laws/GeneralLaws"),
    "MI": ("Michigan Legislature", "https://www.legislature.mi.gov/"),
    "MN": ("Minnesota Statutes", "https://www.revisor.mn.gov/statutes/"),
    "MS": ("Mississippi Legislature", "https://www.legislature.ms.gov/"),
    "MO": ("Revised Statutes of Missouri", "https://revisor.mo.gov/main/Home.aspx"),
    "MT": ("Montana Code Annotated", "https://leg.mt.gov/bills/mca/"),
    "NE": ("Nebraska Revised Statutes", "https://nebraskalegislature.gov/laws/browse-statutes.php"),
    "NV": ("Nevada Revised Statutes", "https://www.leg.state.nv.us/nrs/"),
    "NH": ("New Hampshire RSA", "https://gc.nh.gov/rsa/html/indexes/default.html"),
    "NJ": ("New Jersey Legislature", "https://www.njleg.state.nj.us/"),
    "NM": ("New Mexico Statutes (NMOneSource)", "https://nmonesource.com/nmos/en/nav.do"),
    "NY": ("New York Vehicle and Traffic Law", "https://www.nysenate.gov/legislation/laws/VAT"),
    "NC": ("North Carolina General Statutes", "https://www.ncleg.gov/Laws/GeneralStatutesTOC"),
    "ND": ("North Dakota Century Code", "https://ndlegis.gov/general-information/north-dakota-century-code/index.html"),
    "OH": ("Ohio Revised Code", "https://codes.ohio.gov/ohio-revised-code"),
    "OK": ("Oklahoma Statutes (OSCN)", "https://www.oscn.net/applications/oscn/index.asp?ftdb=STOKST"),
    "OR": ("Oregon Revised Statutes", "https://www.oregonlegislature.gov/bills_laws/Pages/ORS.aspx"),
    "PA": ("Pennsylvania Consolidated Statutes", "https://www.palegis.us/statutes"),
    "RI": ("Rhode Island General Laws", "https://webserver.rilegislature.gov/Statutes/"),
    "SC": ("South Carolina Code of Laws", "https://www.scstatehouse.gov/code/statmast.php"),
    "SD": ("South Dakota Codified Laws", "https://sdlegislature.gov/Statutes"),
    "TN": ("Tennessee General Assembly", "https://www.capitol.tn.gov/"),
    "TX": ("Texas Statutes", "https://statutes.capitol.texas.gov/"),
    "UT": ("Utah Code", "https://le.utah.gov/xcode/code.html"),
    "VT": ("Vermont Statutes", "https://legislature.vermont.gov/statutes/"),
    "VA": ("Code of Virginia", "https://law.lis.virginia.gov/vacode/"),
    "WA": ("Revised Code of Washington", "https://app.leg.wa.gov/rcw/"),
    "WV": ("West Virginia Code", "https://code.wvlegislature.gov/"),
    "WI": ("Wisconsin Statutes", "https://docs.legis.wisconsin.gov/statutes/statutes"),
    "WY": ("Wyoming Legislature", "https://wyoleg.gov/"),
}

NAMES = {
    "AL": "Alabama", "AK": "Alaska", "AZ": "Arizona", "AR": "Arkansas", "CA": "California", "CO": "Colorado",
    "CT": "Connecticut", "DE": "Delaware", "DC": "District of Columbia", "FL": "Florida", "GA": "Georgia",
    "HI": "Hawaii", "ID": "Idaho", "IL": "Illinois", "IN": "Indiana", "IA": "Iowa", "KS": "Kansas", "KY": "Kentucky",
    "LA": "Louisiana", "ME": "Maine", "MD": "Maryland", "MA": "Massachusetts", "MI": "Michigan", "MN": "Minnesota",
    "MS": "Mississippi", "MO": "Missouri", "MT": "Montana", "NE": "Nebraska", "NV": "Nevada", "NH": "New Hampshire",
    "NJ": "New Jersey", "NM": "New Mexico", "NY": "New York", "NC": "North Carolina", "ND": "North Dakota",
    "OH": "Ohio", "OK": "Oklahoma", "OR": "Oregon", "PA": "Pennsylvania", "RI": "Rhode Island", "SC": "South Carolina",
    "SD": "South Dakota", "TN": "Tennessee", "TX": "Texas", "UT": "Utah", "VT": "Vermont", "VA": "Virginia",
    "WA": "Washington", "WV": "West Virginia", "WI": "Wisconsin", "WY": "Wyoming",
}

UT = "https://le.utah.gov/xcode/Title41/Chapter6A/"

# ---- Utah: checked against the statute text on le.utah.gov on REVIEWED ----
UTAH = {
    "confidence": "verified",
    "summary": ("A Segway-Ninebot Max G2 is a 'motor assisted scooter' if it can't exceed 20 mph on the motor alone "
                "(Utah Code 41-6a-102); above 20 mph it counts as a 'high power electric device'. Motor assisted "
                "scooters follow the bicycle rules, may not go faster than 15 mph, and need no licence or "
                "registration. Since 6 May 2026 riders under 21 must wear a helmet. New rules for riders aged "
                "8 to 15 start on 5 May 2027."),
    "scooter": {
        "where": ("Bicycle rules apply (41-6a-1115(1)): ride on the road in the direction of traffic, as far right as "
                  "practicable, in bike lanes and on paths and trails where bicycles are allowed. Not on freeways, "
                  "not in public parking structures, not where bicycles are prohibited by signs. You may cross in a "
                  "crosswalk at a reasonable speed (41-6a-1115)."),
        "sidewalk": ("State law lets cities authorize and regulate scooter riding on sidewalks, including a sidewalk "
                     "speed limit (41-6a-1115.1(3)). Without a city rule allowing it, stay off sidewalks; where it is "
                     "allowed, yield to pedestrians and give an audible signal before passing (41-6a-1106)."),
        "maxSpeed": ("15 mph maximum for a motor assisted scooter (41-6a-1115). The definition covers devices with "
                     "a motor up to 2,000 W and a top speed of 20 mph on a paved level surface (41-6a-102); a device "
                     "that can go faster than 20 mph on the motor alone is a 'high power electric device'."),
        "age": ("Under 8: may not ride with the motor running on public property, roads, paths or sidewalks. From "
                "5 May 2027: ages 8-15 need direct supervision by a parent/responsible adult or a Personal Electric "
                "Vehicle Safety Certificate (41-6a-1115, 41-6a-1512); 16+ may ride unsupervised. Under 16 may not "
                "ride a high power electric device on a highway."),
        "helmet": ("Since 6 May 2026, riders under 21 must wear a helmet meeting 16 CFR 1203 (bicycle standard) on a "
                   "highway; rented scooters are exempt (41-6a-1505). Fine up to $25. High power electric devices "
                   "need a DOT motorcycle helmet (FMVSS 218) for under-21s."),
        "license": ("No driver licence and no registration for a motor assisted scooter (licensing under 53-3-202 "
                    "does not apply, 41-6a-1115). The scooter may not be modified from the manufacturer's design "
                    "(except a rental company lowering the top speed)."),
        "lights": ("Motorcycle equipment rules don't apply (41-6a-1115), but bicycle lighting does: at night a white "
                   "front lamp visible 500 ft, a red rear reflector or lamp visible 500 ft, and side reflectors or "
                   "side-visible light (41-6a-1114)."),
        "parking": ("Cities regulate scooter parking and shared-scooter staging (41-6a-1115.1). Don't block "
                    "sidewalks, ramps, building entrances or traffic; follow city rules."),
        "notes": ("One rider only (no passengers beyond the design). No alcohol or open containers while riding "
                  "(41-6a-526). Violations are infractions; owners may not let under-16s ride in violation. A local "
                  "ordinance can add rules: check the city entry below."),
    },
    "ebike": {
        "classes": ("Electric assisted bicycle: up to 750 W, working pedals. Class 1: pedal assist to 20 mph. "
                    "Class 2: throttle, no assist above 20 mph. Class 3: pedal assist to 28 mph with a speedometer "
                    "(41-6a-102). A tampered or modified e-bike that goes over 20 mph on the motor alone becomes a "
                    "high power electric device."),
        "where": ("Bicycle rules; may use paths and trails designated for bicycles. Cities and state agencies can "
                  "restrict e-bikes or a class of e-bike on sidewalks, paths and trails (41-6a-1115.5)."),
        "age": ("Under 8 may not ride with the motor engaged. Until 5 May 2027: under 14 needs parent/guardian "
                "supervision and under 16 may not ride a Class 3. From 5 May 2027: ages 8-15 need supervision or "
                "the Personal Electric Vehicle Safety Certificate (41-6a-1115.5)."),
        "helmet": "Under 21 must wear a helmet on a highway since 6 May 2026, except on a rented Class 1 (41-6a-1505).",
        "license": "No licence or registration for an e-bike that meets the definition.",
    },
    "sources": [
        {"title": "Utah Code 41-6a-1115 Motor assisted scooters (current)", "url": UT + "C41-6a-S1115_2019051420190514.html"},
        {"title": "Utah Code 41-6a-1115 (version effective 5 May 2027)", "url": UT + "C41-6a-S1115_2026050620270505.html"},
        {"title": "Utah Code 41-6a-1115.1 Local ordinances, scooter-share", "url": UT + "C41-6a-S1115.1_2024050120240501.html"},
        {"title": "Utah Code 41-6a-1115.5 Electric assisted bicycles", "url": UT + "C41-6a-S1115.5_2024050120240501.html"},
        {"title": "Utah Code 41-6a-1505 Helmets (under 21)", "url": UT + "C41-6a-S1505_2026050620260506.html"},
        {"title": "Utah Code 41-6a-1512 Personal electric vehicle safety certificate (2027)", "url": UT + "C41-6a-S1512_2026050620270505.html"},
        {"title": "Utah Code 41-6a-102 Definitions", "url": UT + "C41-6a-S102_2026050620261001.html"},
        {"title": "Utah Code 41-6a-1114 Bicycle lamps and reflectors", "url": UT + "C41-6a-S1114_1800010118000101.html"},
        {"title": "Utah Code 41-6a-1106 Sidewalks, paths, yielding to pedestrians", "url": UT + "C41-6a-S1106_2018050820180508.html"},
    ],
}

# ---- Other states: condensed summaries (confidence "summary") ----
S = {}

S["CA"] = {
    "summary": "California calls these 'motorized scooters' (Vehicle Code 407.5). Bike lanes and slower streets, 15 mph max, licence or permit needed, no sidewalks.",
    "scooter": {
        "where": "Ride in a bike lane when there is one. Allowed on streets with a speed limit up to 25 mph, or up to 35 mph inside a Class II or IV bikeway (VC 21235).",
        "sidewalk": "Not allowed on sidewalks except to enter or leave adjacent property (VC 21235).",
        "maxSpeed": "15 mph (VC 21235).",
        "age": "Must hold a driver licence or instruction permit (VC 21235), so in practice 16+.",
        "helmet": "Required under 18 (VC 21235).",
        "license": "Driver licence or permit required; no registration or insurance.",
        "lights": "At night: white headlamp, red rear reflector, white or yellow side reflectors (VC 21223).",
        "notes": "No passengers; may not leave a scooter lying on a sidewalk in a way that blocks pedestrians.",
    },
    "ebike": {"classes": "Classes 1, 2 and 3 (VC 312.5). Class 3: 16+, helmet for all ages, not on bike paths unless allowed locally.",
              "helmet": "Under 18 on any e-bike; everyone on Class 3."},
    "sources": [{"title": "California Vehicle Code 21235", "url": "https://leginfo.legislature.ca.gov/faces/codes_displaySection.xhtml?lawCode=VEH&sectionNum=21235."}],
}
S["NY"] = {
    "summary": "New York allows 'bicycles with electric assist' (Classes 1-3) and 'electric scooters' (VTL 114-e), but cities and towns decide whether scooters may be used locally.",
    "scooter": {
        "where": "Only where the local government allows it. Not on roads with a speed limit over 30 mph except to cross; ride in bike lanes when present (VTL 1281).",
        "sidewalk": "Not on sidewalks unless a local law allows it (VTL 1281).",
        "maxSpeed": "15 mph (VTL 114-e, 1281).",
        "age": "16 and older.",
        "helmet": "Required for ages 16-17.",
        "license": "No licence or registration.",
        "lights": "At night: white front light and red rear light.",
        "notes": "New York City allows e-scooters and Class 1-3 e-bikes in bike lanes and on streets; NYC parks have extra rules.",
    },
    "ebike": {"classes": "Class 1 and 2 to 20 mph; Class 3 to 25 mph, allowed only in cities with over 1 million people (NYC) (VTL 102-c).",
              "age": "Class 3: 16+.", "helmet": "Class 3: required for all riders."},
    "sources": [{"title": "NY Vehicle and Traffic Law 114-E (electric scooter)", "url": "https://www.nysenate.gov/legislation/laws/VAT/114-E"},
                {"title": "NY Vehicle and Traffic Law 1281", "url": "https://www.nysenate.gov/legislation/laws/VAT/1281"}],
}
S["MN"] = {
    "summary": "Minnesota 'motorized foot scooters' (Statute 169.225): age 12+, 15 mph, no sidewalks, helmet under 18.",
    "scooter": {
        "where": "As far right as practicable on the road, or on bike lanes and paths.",
        "sidewalk": "Not on sidewalks except to enter or leave a driveway or parking area.",
        "maxSpeed": "15 mph.",
        "age": "12 and older.",
        "helmet": "Required under 18.",
        "license": "No licence or registration.",
        "lights": "At night: headlight required.",
    },
    "ebike": {"classes": "Electric-assisted bicycles in Classes 1-3 (Statute 169.011)."},
    "sources": [{"title": "Minnesota Statutes 169.225", "url": "https://www.revisor.mn.gov/statutes/cite/169.225"}],
}
S["OR"] = {
    "summary": "Oregon 'motor assisted scooters' (ORS 801.348): 16+, 15 mph, no sidewalks, no licence.",
    "scooter": {
        "where": "Bike lanes and roads with a speed limit up to 25 mph (faster roads only in a bike lane).",
        "sidewalk": "Not on sidewalks except to cross to or from a driveway.",
        "maxSpeed": "15 mph.",
        "age": "16 and older.",
        "helmet": "Helmet rules apply to young riders; adults may ride without one.",
        "license": "No licence or registration.",
    },
    "ebike": {"classes": "Oregon defines 'electric assisted bicycles' (up to 1,000 W, 20 mph); riders 16+."},
    "sources": [],
}
S["TX"] = {
    "summary": "Texas 'motor-assisted scooters' (Transportation Code 551.351-.352) may use streets up to 35 mph and bike paths; cities can set rules.",
    "scooter": {
        "where": "On streets with a speed limit of 35 mph or less, and on paths set aside for bicycles. Cities may prohibit or regulate.",
        "sidewalk": "Allowed on sidewalks where not prohibited locally (yield to pedestrians).",
        "maxSpeed": "No state cap for these devices; cities often set one.",
        "license": "No licence or registration.",
    },
    "ebike": {"classes": "Classes 1-3 (Transportation Code 664)."},
    "sources": [{"title": "Texas Transportation Code chapter 551", "url": "https://statutes.capitol.texas.gov/Docs/TN/htm/TN.551.htm"}],
}
S["VA"] = {
    "summary": "Virginia 'motorized skateboards or scooters' (Code 46.2-908.1): 20 mph on roads, age 14+ unless supervised, sidewalks allowed unless locally prohibited.",
    "scooter": {
        "where": "Roads (not on roads with a speed limit over 25 mph unless in a bike lane), bike lanes, shared-use paths.",
        "sidewalk": "Allowed unless a city or county prohibits it; yield to pedestrians.",
        "maxSpeed": "20 mph.",
        "age": "14 and older, or younger under adult supervision.",
        "license": "No licence or registration.",
    },
    "ebike": {"classes": "Classes 1-3 (Code 46.2-100)."},
    "sources": [{"title": "Code of Virginia 46.2-908.1", "url": "https://law.lis.virginia.gov/vacode/title46.2/chapter8/section46.2-908.1/"}],
}
S["WA"] = {
    "summary": "Washington 'motorized foot scooters' (RCW 46.61.710): roads up to 25 mph and bike lanes; cities set more rules.",
    "scooter": {
        "where": "Roads with a speed limit of 25 mph or less, bike lanes, and paths where allowed locally.",
        "sidewalk": "Cities may allow or ban sidewalk riding.",
        "maxSpeed": "Device top speed up to 20 mph.",
        "license": "No licence or registration.",
    },
    "ebike": {"classes": "Classes 1-3 (RCW 46.04.169). Class 3: 16+."},
    "sources": [{"title": "RCW 46.61.710", "url": "https://app.leg.wa.gov/rcw/default.aspx?cite=46.61.710"}],
}
S["MA"] = {
    "summary": "Massachusetts treats 'motorized scooters' strictly (MGL c.90 s.1E): licence or learner's permit, helmet, no sidewalks, 20 mph.",
    "scooter": {
        "where": "Roads, as far right as practicable.",
        "sidewalk": "Not on sidewalks.",
        "maxSpeed": "20 mph.",
        "age": "Must hold a licence or learner's permit (16+).",
        "helmet": "Required.",
        "license": "Licence or learner's permit required.",
    },
    "ebike": {"classes": "Massachusetts has not adopted the 3-class system; e-bikes may be treated as motorized bicycles (licence required)."},
    "sources": [{"title": "MGL chapter 90 section 1E", "url": "https://malegislature.gov/Laws/GeneralLaws/PartI/TitleXIV/Chapter90/Section1E"}],
}
S["AZ"] = {
    "summary": "Arizona 'electric standup scooters' (ARS 28-101) are treated like bicycles: 20 mph device limit, no licence or registration; cities set local rules.",
    "scooter": {"where": "Bicycle rules apply; cities (e.g. Phoenix, Tempe, Tucson) regulate further.", "maxSpeed": "Device top speed up to 20 mph.",
                "license": "No licence, registration or insurance."},
    "ebike": {"classes": "Classes 1-3 (ARS 28-101)."},
    "sources": [{"title": "ARS 28-101 Definitions", "url": "https://www.azleg.gov/ars/28/00101.htm"}],
}
S["CO"] = {
    "summary": "Colorado 'electric scooters' are treated much like e-bikes; local governments decide on sidewalks and paths.",
    "scooter": {"where": "Roads and bike lanes; local rules for paths.", "sidewalk": "Only where a local government allows it.",
                "maxSpeed": "Device top speed up to 20 mph.", "license": "No licence or registration."},
    "ebike": {"classes": "Classes 1-3 (CRS 42-1-102). Class 3: 16+, helmet under 18."},
    "sources": [],
}
S["CT"] = {
    "summary": "Connecticut allows 'electric foot scooters' (2019): 16+, 20 mph, helmet under 18, towns may restrict.",
    "scooter": {"maxSpeed": "20 mph.", "age": "16 and older.", "helmet": "Required under 18.", "license": "No licence or registration.",
                "where": "Roads and bike lanes; towns may restrict."},
    "ebike": {"classes": "Classes 1-3 (since 2016)."},
    "sources": [],
}
S["MD"] = {
    "summary": "Maryland 'electric low speed scooters': 20 mph, bike lanes and roads up to 30 mph, sidewalks only where local law allows.",
    "scooter": {"where": "Bike lanes, and roads with a speed limit of 30 mph or less.", "sidewalk": "Only where allowed by local law.",
                "maxSpeed": "20 mph.", "license": "No licence or registration."},
    "ebike": {"classes": "Classes 1-3 (since 2019)."},
    "sources": [],
}
S["NJ"] = {
    "summary": "New Jersey treats low-speed e-scooters (under 19 mph) and low-speed e-bikes like bicycles (2019 law): no licence, registration or insurance.",
    "scooter": {"where": "Bicycle rules apply.", "maxSpeed": "Device top speed under 19 mph.", "license": "No licence, registration or insurance."},
    "ebike": {"classes": "Class 1 and 2 'low-speed electric bicycles' are treated like bicycles; Class 3 / faster ones are motorized bicycles."},
    "sources": [],
}
S["PA"] = {
    "summary": "Pennsylvania has no general category for stand-up e-scooters, so riding a privately owned one on public roads is generally not allowed outside approved programs.",
    "scooter": {"where": "Generally not legal on public roads or sidewalks; check local pilot programs.", "license": "No legal registration path for stand-up scooters."},
    "ebike": {"classes": "'Pedalcycle with electric assist': up to 750 W and 20 mph; treated as a bicycle."},
    "sources": [],
}
S["WI"] = {
    "summary": "Wisconsin defines 'electric scooters' (up to 20 mph) and treats them much like bicycles; cities may set rules.",
    "scooter": {"maxSpeed": "20 mph.", "where": "Bicycle rules apply; local rules for sidewalks.", "license": "No licence or registration."},
    "ebike": {"classes": "Classes 1-3."},
    "sources": [],
}
S["IN"] = {
    "summary": "Indiana 'electric foot scooters' (2019) follow the bicycle rules: 20 mph device limit, no licence or registration.",
    "scooter": {"where": "Bicycle rules apply; cities may regulate.", "maxSpeed": "Device top speed up to 20 mph.", "license": "No licence or registration."},
    "ebike": {"classes": "Classes 1-3."},
    "sources": [],
}
S["DC"] = {
    "summary": "Washington DC allows 'personal mobility devices' on roads and bike lanes, and on sidewalks outside the Central Business District.",
    "scooter": {"sidewalk": "Allowed outside the Central Business District; not on downtown sidewalks.", "maxSpeed": "10 mph on sidewalks; device top speed 20 mph.",
                "helmet": "Required under 16.", "license": "No licence or registration."},
    "ebike": {"classes": "Motorized bicycles up to 20 mph treated like bicycles."},
    "sources": [],
}
S["FL"] = {
    "summary": "Florida 'motorized scooters' and 'micromobility devices' (FS 316.003) have the rights and duties of bicycle riders; local governments regulate.",
    "scooter": {"where": "Bicycle rules apply; counties and cities may regulate or prohibit.", "license": "No licence or registration.",
                "helmet": "Under 16 on bicycles (FS 316.2065)."},
    "ebike": {"classes": "Classes 1-3 (FS 316.003); up to 750 W and 28 mph."},
    "sources": [],
}
S["AK"] = {"ebike": {"classes": "Alaska has not adopted the 3-class system; e-bikes may be treated as motor-driven cycles (licence may be required)."}}

# One-wheel boards (Onewheel, VESC "float" boards). Most states' "electric personal assistive mobility device"
# (EPAMD) definitions need two side-by-side wheels and e-scooter definitions need handlebars, so a one-wheel
# board usually isn't covered by either: the default says so. UT and CA were checked against the statute text.
BOARDS_DEFAULT = {
    "category": "Not specifically addressed",
    "text": ("This state's code (as summarised here) has no rules written for one-wheel self-balancing boards. They "
             "usually don't fit the 'electric personal assistive mobility device' definition (two side-by-side wheels) "
             "or the e-scooter definition (handlebars), so local rules for skateboards and motorized devices are what "
             "apply in practice. Check your city's ordinances and park/trail rules before riding."),
    "confidence": "summary",
    "sources": [],
}
BOARDS = {
    "UT": {
        "category": "Self-balancing electric skateboard",
        "text": ("Utah defines a 'self-balancing electric skateboard' (Utah Code 41-6a-102): a skateboard-like device with a "
                 "single wheel and an electric motor that can't go faster than 20 mph on the motor alone, ridden facing "
                 "sideways. That covers a stock Onewheel. It is not an 'electric personal assistive mobility device' (that "
                 "needs two side-by-side wheels) and not a 'motor assisted scooter' (needs two wheels and handlebars). "
                 "No separate operating rules for it were found in Title 41 Chapter 6a on the review date, so skateboard "
                 "rules and local ordinances apply; some cities (e.g. Draper) ban them on trails, paths and in parks. "
                 "A board that can go faster than 20 mph on the motor (e.g. a GT-S, or a tuned VESC board) is a 'high "
                 "power electric device' since 6 May 2026 (41-6a-1511): rider 16 or older, a class D licence on highways "
                 "(53-3-202), helmet under 21, never on freeways, and cities may restrict it on sidewalks, paths and trails."),
        "confidence": "verified",
        "sources": [
            {"title": "Utah Code 41-6a-102 Definitions (self-balancing electric skateboard, EPAMD)", "url": UT + "C41-6a-S102_2026050620261001.html"},
            {"title": "Utah H.B. 381 (2026) Electric Mobility Device Amendments (high power electric devices, 41-6a-1511)",
             "url": "https://le.utah.gov/Session/2026/bills/enrolled/HB0381.pdf"},
        ],
    },
    "CA": {
        "category": "Electrically motorized board",
        "text": ("California's 'electrically motorized board' (Vehicle Code 313.5: a floorboard you stand on, one rider, under "
                 "1,000 W average, 20 mph max on the motor) covers one-wheel boards. Rider 16 or older (21291); bicycle "
                 "helmet required on highways, bikeways, paths, sidewalks and trails (21292); at night a white front light "
                 "and red/white/yellow reflectors (21293); only on roads with a speed limit of 35 mph or less unless in a "
                 "Class II or IV bikeway, and never faster than 15 mph (21294); no riding under the influence (21296). "
                 "Cities may regulate further. A board faster than 20 mph isn't covered by this definition."),
        "confidence": "summary",
        "sources": [
            {"title": "California Vehicle Code 313.5 (electrically motorized board)",
             "url": "https://leginfo.legislature.ca.gov/faces/codes_displaySection.xhtml?lawCode=VEH&sectionNum=313.5"},
            {"title": "California Vehicle Code 21290-21296 (operation of electrically motorized boards)",
             "url": "https://leginfo.legislature.ca.gov/faces/codes_displayText.xhtml?lawCode=VEH&division=11.&chapter=1.&article=7."},
        ],
    },
}

ROWS = []
for code, name in NAMES.items():
    if code == "UT":
        e = dict(UTAH)
    else:
        e = S.get(code, {})
        e = {
            "confidence": "summary",
            "summary": e.get("summary", f"{name}: summary only. Rules for e-scooters vary by state and city; check the state code and your city's ordinances."),
            "scooter": {"where": SCOOTER_GENERIC_WHERE, **e.get("scooter", {})},
            "ebike": {"classes": EBIKE_GENERIC, **e.get("ebike", {})},
            "sources": e.get("sources", []),
        }
        e["scooter"].setdefault("lights", LIGHTS_GENERIC)
        e["ebike"].setdefault("where", "Generally bicycle rules apply; paths and sidewalks per state and local rules.")
    leg = LEG[code]
    e["sources"] = e["sources"] + [{"title": leg[0], "url": leg[1]}, NCSL]
    e["boards"] = dict(BOARDS.get(code, BOARDS_DEFAULT))
    ROWS.append({"code": code, "name": name, "reviewed": REVIEWED, **e})

CITY_UT_COMMON_SRC = {"title": "Utah Code 41-6a-1115.1 (cities may regulate scooters and authorize sidewalk riding)", "url": UT + "C41-6a-S1115.1_2024050120240501.html"}

CITIES = [
    {"state": "UT", "city": "St. George", "aliases": ["Saint George", "St George"],
     "summary": ("St. George follows Utah's scooter law. City police have said the city code does not authorize "
                 "scooter riding on sidewalks, so ride in the street, in bike lanes, or on the paved trail system "
                 "where bicycles are allowed."),
     "scooter": {"sidewalk": "Not authorized by city code: use the street, bike lanes or bike-legal trails.",
                 "where": "Streets and bike lanes; the city's paved multi-use trails where bicycles are allowed and not signed otherwise.",
                 "notes": "Some trails and parks post e-bike/e-scooter restrictions; obey posted signs."},
     "ebike": {"where": "Paved city trails allow bicycles; check posted signs for e-bike class limits."},
     "sources": [{"title": "St. George Municipal Code", "url": "https://stgeorge.municipal.codes/"}, CITY_UT_COMMON_SRC]},
    {"state": "UT", "city": "Cedar City", "aliases": [],
     "summary": "No Cedar City scooter ordinance was found in this review: Utah state rules apply. Assume no sidewalk riding unless posted.",
     "scooter": {"sidewalk": "Not authorized unless the city allows it (state default)."},
     "ebike": {}, "sources": [{"title": "Cedar City Code", "url": "https://www.cedarcityut.gov/"}, CITY_UT_COMMON_SRC]},
    {"state": "UT", "city": "Hurricane", "aliases": [],
     "summary": "No Hurricane-specific scooter ordinance was found in this review: Utah state rules apply. Many trails nearby are on federal land with their own e-bike/motor rules.",
     "scooter": {"sidewalk": "Not authorized unless the city allows it (state default).",
                 "notes": "BLM/National Park trails (e.g. near Zion) have separate rules for motorized devices."},
     "ebike": {}, "sources": [{"title": "Hurricane City", "url": "https://www.cityofhurricane.com/"}, CITY_UT_COMMON_SRC]},
    {"state": "UT", "city": "Washington", "aliases": ["Washington City"],
     "summary": "No Washington City scooter ordinance was found in this review: Utah state rules apply. The city's paved trails connect to the St. George trail network.",
     "scooter": {"sidewalk": "Not authorized unless the city allows it (state default)."},
     "ebike": {}, "sources": [{"title": "Washington City", "url": "https://washingtoncity.org/"}, CITY_UT_COMMON_SRC]},
    {"state": "UT", "city": "Salt Lake City", "aliases": ["SLC"],
     "summary": ("Salt Lake City treats scooters like bicycles and bans riding on downtown sidewalks (roughly North "
                 "Temple to 500 South, 400 West to 200 East). Shared scooters have parking rules (e.g. not within "
                 "15 ft of building entrances)."),
     "scooter": {"sidewalk": "Not on downtown sidewalks; elsewhere yield to pedestrians where sidewalk riding isn't prohibited.",
                 "parking": "Park upright out of the walkway; not blocking entrances, ramps or transit stops."},
     "ebike": {}, "sources": [{"title": "Salt Lake City Code (American Legal)", "url": "https://codelibrary.amlegal.com/codes/saltlakecityut/latest/overview"}, CITY_UT_COMMON_SRC]},
    {"state": "UT", "city": "Provo", "aliases": [],
     "summary": "Provo amended its micromobility rules in 2023 (Ordinance 2023-10), including sidewalk riding limits in busy areas. Check the city code for the exact zones.",
     "scooter": {"sidewalk": "Restricted in parts of the city (Ordinance 2023-10); yield to pedestrians elsewhere."},
     "ebike": {}, "sources": [{"title": "Provo Ordinance 2023-10 (micromobility)", "url": "https://provo.municipal.codes/enactments/Ord2023-10/media/original.pdf"},
                              {"title": "Provo City Code", "url": "https://provo.municipal.codes/"}, CITY_UT_COMMON_SRC]},
    {"state": "UT", "city": "Ogden", "aliases": [],
     "summary": "No Ogden scooter ordinance details were confirmed in this review: Utah state rules apply; check the city code for downtown sidewalk limits.",
     "scooter": {"sidewalk": "Not authorized unless the city allows it (state default)."},
     "ebike": {}, "sources": [{"title": "Ogden City", "url": "https://www.ogdencity.gov/"}, CITY_UT_COMMON_SRC]},
    {"state": "UT", "city": "Logan", "aliases": [],
     "summary": "Logan's traffic code (Title 10) governs local riding rules; Utah state rules apply otherwise.",
     "scooter": {"sidewalk": "Not authorized unless the city allows it (state default)."},
     "ebike": {}, "sources": [{"title": "Logan Municipal Code (American Legal)", "url": "https://codelibrary.amlegal.com/codes/loganut/latest/logan_ut/0-0-0-5578"}, CITY_UT_COMMON_SRC]},
    # Major cities whose scooter rules differ from their state's.
    {"state": "NY", "city": "New York", "aliases": ["New York City", "NYC", "Manhattan", "Brooklyn", "Queens", "Bronx", "Staten Island"],
     "summary": "NYC allows e-scooters and Class 1-3 e-bikes (Class 3 limited to 25 mph) on streets and in bike lanes; not on sidewalks. City speed limit for e-bikes/e-scooters is 15 mph.",
     "scooter": {"sidewalk": "Not allowed.", "maxSpeed": "15 mph in the city."},
     "ebike": {}, "sources": [{"title": "NYC DOT: E-bikes and e-scooters", "url": "https://www.nyc.gov/html/dot/html/bicyclists/ebikes.shtml"}]},
    {"state": "CA", "city": "San Francisco", "aliases": [],
     "summary": "San Francisco: no sidewalk riding; shared scooters must be parked in designated spots or out of the walkway.",
     "scooter": {"sidewalk": "Not allowed."}, "ebike": {},
     "sources": [{"title": "SFMTA Powered Scooter Share program", "url": "https://www.sfmta.com/projects/powered-scooter-share-permit-and-pilot-program"}]},
    {"state": "CA", "city": "Los Angeles", "aliases": [],
     "summary": "Los Angeles follows the California Vehicle Code (no sidewalks, 15 mph) and regulates shared dockless scooters.",
     "scooter": {"sidewalk": "Not allowed (state law)."}, "ebike": {},
     "sources": [{"title": "LADOT Dockless Mobility", "url": "https://ladot.lacity.gov/projects/transportation-services/shared-mobility/micromobility"}]},
    {"state": "AZ", "city": "Phoenix", "aliases": [],
     "summary": "Phoenix allows scooters on streets and in bike lanes, not on sidewalks; shared scooters in designated zones.",
     "scooter": {"sidewalk": "Not allowed."}, "ebike": {},
     "sources": [{"title": "City of Phoenix (search: e-scooter)", "url": "https://www.phoenix.gov/"}]},
    {"state": "CO", "city": "Denver", "aliases": [],
     "summary": "Denver: e-scooters ride in bike lanes or streets with speed limits up to 30 mph; sidewalks only when no other option, at 6 mph.",
     "scooter": {"sidewalk": "Only when there is no bike lane and the street limit is over 30 mph, at 6 mph."}, "ebike": {},
     "sources": [{"title": "City and County of Denver (search: scooter rules)", "url": "https://www.denvergov.org/"}]},
    {"state": "NV", "city": "Las Vegas", "aliases": [],
     "summary": "Las Vegas: private scooters follow Nevada law plus city rules; casinos and the Strip pedestrian bridges commonly prohibit riding.",
     "scooter": {}, "ebike": {},
     "sources": [{"title": "City of Las Vegas", "url": "https://www.lasvegasnevada.gov/"}]},
    {"state": "TX", "city": "Austin", "aliases": [],
     "summary": "Austin: scooters may use streets and bike lanes; sidewalk riding is restricted downtown and where posted. Check the city page for current rules.",
     "scooter": {"sidewalk": "Restricted downtown and where posted."}, "ebike": {},
     "sources": [{"title": "City of Austin (search: shared micromobility)", "url": "https://www.austintexas.gov/"}]},
    {"state": "DC", "city": "Washington", "aliases": ["Washington, D.C.", "Washington DC"],
     "summary": "No sidewalk riding in the Central Business District; elsewhere sidewalks are allowed at a walking-friendly speed.",
     "scooter": {"sidewalk": "Not in the Central Business District."}, "ebike": {},
     "sources": [{"title": "District Department of Transportation (DDOT)", "url": "https://ddot.dc.gov/"}]},
]

out = {
    "reviewed": REVIEWED,
    "disclaimer": ("Short summaries to help you ride legally, not legal advice. Laws change: check the linked source for the "
                   "exact wording. Utah entries were checked against the Utah Code on the review date."),
    "boards_default": BOARDS_DEFAULT,
    "states": ROWS,
    "cities": [{"reviewed": REVIEWED, **c} for c in CITIES],
}
dest = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "regulations.json")
with open(dest, "w", encoding="utf-8", newline="\n") as f:
    json.dump(out, f, indent=1, ensure_ascii=False)
print("wrote", os.path.normpath(dest), len(ROWS), "states,", len(CITIES), "cities")
