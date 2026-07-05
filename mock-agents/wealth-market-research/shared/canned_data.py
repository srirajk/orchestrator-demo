"""
Canned market research content for the Wealth Market Research agent.

Covers four topic buckets (equities, fixed_income, alternatives, macro)
plus a broad overview. Content reflects a plausible H1 2026 house view:
  - US equities: still constructive but late-cycle caution
  - Fixed income: duration recovery thesis with curve steepening
  - Alternatives: private credit in favour; real assets hedge
  - Macro: soft-landing consensus with tail risks

All figures, dates, and outlooks are FICTIONAL demo content and must not
be construed as investment advice.
"""

# topic → research record (None key = broad overview / no topic specified)
MARKET_RESEARCH: dict = {
    None: {
        "topic": "broad_overview",
        "title": "Meridian House View — Q2/Q3 2026 Quarterly Outlook",
        "published_date": "2026-06-20",
        "classification": "internal",
        "sections": [
            {
                "heading": "Executive Summary",
                "body": (
                    "Our base case remains a US soft landing with growth moderating to 1.8 % in "
                    "2026. The Fed completed its cutting cycle at 4.25 %, and market pricing "
                    "implies one additional 25 bp reduction by year-end. Equity valuations are "
                    "stretched on forward P/E (S&P 500 at 21x) but earnings revision breadth "
                    "turned positive in May, providing a near-term cushion. We maintain a slight "
                    "overweight to developed-market equities and a neutral duration stance in "
                    "fixed income while rotating into private credit within alternatives."
                ),
            },
            {
                "heading": "Equities",
                "body": (
                    "US equities: Neutral-to-constructive. Mega-cap technology continues to "
                    "drive index-level earnings, with the 'Magnificent Five' accounting for 36 % "
                    "of S&P 500 EPS. We prefer quality-factor tilt and see Healthcare and "
                    "Industrials as the best risk-adjusted sectors for H2 2026. "
                    "European equities: Modest overweight — valuations at 13x 2027e EPS vs. "
                    "21x US; ECB is ahead of the Fed in the rate cycle. Emerging markets: "
                    "Underweight — China property overhang and USD strength suppress upside."
                ),
            },
            {
                "heading": "Fixed Income",
                "body": (
                    "US Treasuries: Neutral duration (benchmark). 10-year yield trading "
                    "in 4.15 %–4.55 % band; we expect gradual steepening as term premium "
                    "rebuilds. Investment-grade credit: Overweight — spread compression "
                    "limited but carry attractive vs. cash. High yield: Underweight — "
                    "spreads (OAS 320 bp) do not compensate for default risk pickup in a "
                    "slower-growth environment. Emerging-market debt: Selective — local-"
                    "currency EM yields offer value where current-account fundamentals are "
                    "sound (Brazil, India, Indonesia)."
                ),
            },
            {
                "heading": "Alternatives",
                "body": (
                    "Private credit: Overweight — senior secured floating-rate loans yield "
                    "10 %–12 % net; default rates remain below historical averages at 1.8 %. "
                    "Infrastructure: Overweight — rate sensitivity has abated; data-centre and "
                    "energy-transition assets attract long-duration capital. Real estate: "
                    "Neutral — office sector continues to de-rate; logistics and residential "
                    "are stabilising. Hedge funds: Neutral — macro strategies outperforming; "
                    "long/short equity struggling with correlation compression."
                ),
            },
            {
                "heading": "Key Risks",
                "body": (
                    "1. Re-acceleration of US inflation forcing the Fed to pivot hawkish "
                    "(probability 15 %). "
                    "2. China hard landing spilling into global supply chains (probability 20 %). "
                    "3. Geopolitical escalation in the Middle East lifting energy prices > $100/bbl "
                    "(probability 12 %). "
                    "4. US fiscal deterioration triggering a term-premium spike above 200 bp "
                    "(probability 18 %)."
                ),
            },
        ],
        "asset_allocation_model": {
            "equities": {"weight_pct": 55, "change": "+2", "stance": "slight overweight"},
            "fixed_income": {"weight_pct": 28, "change": "0", "stance": "neutral"},
            "alternatives": {"weight_pct": 12, "change": "+3", "stance": "overweight"},
            "cash": {"weight_pct": 5, "change": "-5", "stance": "underweight"},
        },
        "as_of_date": "2026-06-20",
    },

    "equities": {
        "topic": "equities",
        "title": "Meridian Equity Sector Outlook — Q3 2026",
        "published_date": "2026-06-18",
        "classification": "internal",
        "sections": [
            {
                "heading": "US Equity House View",
                "body": (
                    "We maintain a neutral-to-constructive stance on US equities heading into "
                    "Q3 2026. The S&P 500 forward P/E of 21.4x (NTM consensus) is above the "
                    "10-year median of 18.2x, flagging limited valuation support. However, "
                    "Q1 2026 earnings beat rate was 74 % — above the long-run average of "
                    "68 % — and EPS revisions have inflected positive for the first time "
                    "since Q3 2025. We see the index trading in the 5,400–5,900 range "
                    "over the next 6 months, with the skew to the downside if the macro "
                    "soft-landing thesis fails."
                ),
            },
            {
                "heading": "Sector Preferences",
                "body": (
                    "OVERWEIGHT: Healthcare (defensive earnings, secular aging tailwind, "
                    "attractive 14x P/E vs. 21x market), Industrials (reshoring capex super-"
                    "cycle; aerospace and defence order books at record levels), Energy "
                    "Infrastructure (FCF yield 9 %, dividend growth 7 %). "
                    "NEUTRAL: Technology (AI capex driving mega-cap growth but rising "
                    "regulatory headwinds in EU and US; concentration risk elevated), "
                    "Financials (net interest income plateauing; credit quality holding). "
                    "UNDERWEIGHT: Consumer Discretionary (savings rate decline unsustainable; "
                    "student-loan drag returning), Real Estate (office sector secular decline "
                    "unresolved), Utilities (rate-sensitive; still grappling with grid-upgrade costs)."
                ),
            },
            {
                "heading": "International Equities",
                "body": (
                    "Europe: Modest overweight — MSCI Europe trades at 12.8x 2027e EPS "
                    "vs. 21.4x for the S&P 500. ECB is 50 bp ahead of the Fed in its "
                    "cutting cycle. Value factor dominates; prefer German Industrials and "
                    "UK financials. "
                    "Japan: Neutral — Topix corporate governance reform is a multi-year "
                    "positive, but yen strengthening from BoJ normalisation creates a "
                    "headwind for exporters. "
                    "Emerging Markets: Underweight — China structural de-rating (property "
                    "debt workout far from complete), USD strength from US rate differential, "
                    "and geopolitical premium in Taiwan."
                ),
            },
            {
                "heading": "Key Equity Risk Factors",
                "body": (
                    "• Earnings disappointment: consensus 2026 EPS growth at 11 % appears "
                    "optimistic if growth slows to sub-2 % GDP. "
                    "• Concentration: top-10 S&P names represent 36 % of market cap — "
                    "adverse news in AI/cloud supply chains could de-rate the entire index. "
                    "• Positioning: institutional equity allocations at 78th percentile "
                    "historically — marginal buyer pool is thin."
                ),
            },
        ],
        "sector_ratings": {
            "Healthcare":            {"rating": "overweight",  "target_return_12m_pct": 12},
            "Industrials":           {"rating": "overweight",  "target_return_12m_pct": 11},
            "Energy Infrastructure": {"rating": "overweight",  "target_return_12m_pct": 14},
            "Technology":            {"rating": "neutral",     "target_return_12m_pct": 9},
            "Financials":            {"rating": "neutral",     "target_return_12m_pct": 8},
            "Consumer Discretionary":{"rating": "underweight", "target_return_12m_pct": 4},
            "Real Estate":           {"rating": "underweight", "target_return_12m_pct": 2},
            "Utilities":             {"rating": "underweight", "target_return_12m_pct": 5},
        },
        "as_of_date": "2026-06-18",
    },

    "fixed_income": {
        "topic": "fixed_income",
        "title": "Meridian Fixed Income Strategy — Q3 2026",
        "published_date": "2026-06-17",
        "classification": "internal",
        "sections": [
            {
                "heading": "Rates Outlook",
                "body": (
                    "The Fed completed its cutting cycle with the 25 bp reduction on "
                    "28 May 2026, bringing the target range to 4.00 %–4.25 %. "
                    "Our rates team forecasts 10-year UST yields at 4.20 %–4.50 % "
                    "through year-end, with a bear steepening bias as term premium "
                    "normalises (30-year currently 4.75 %). The 2s10s curve has "
                    "un-inverted and is now +35 bp — we expect further steepening "
                    "to +75 bp by Q4 2026."
                ),
            },
            {
                "heading": "Credit Strategy",
                "body": (
                    "Investment-Grade (IG): Overweight. IG spreads at OAS +82 bp "
                    "are inside long-run averages (+110 bp) but the all-in yield "
                    "of 5.25 % on the Bloomberg US IG Index is the highest since 2011 "
                    "and provides a meaningful cushion. Prefer 5–7 year maturity "
                    "bucket; short duration IG for clients needing lower rate sensitivity. "
                    "High Yield (HY): Underweight. BB/B spreads at +320 bp OAS "
                    "do not compensate for rising default risk as refinancing walls "
                    "approach in 2027–2028. Net debt/EBITDA for HY issuers rose "
                    "to 4.8x in Q1 2026, the highest since 2020. "
                    "Leveraged Loans: Underweight. Floating-rate relief is already "
                    "priced; PIK election rates rising (now 12 % of portfolio companies)."
                ),
            },
            {
                "heading": "International Fixed Income",
                "body": (
                    "Gilts: Neutral — Bank of England has cut 75 bp year-to-date; "
                    "sticky services inflation (4.8 %) limits further easing. "
                    "Bunds: Neutral — ECB deposit rate at 2.50 %; bund yields provide "
                    "limited carry vs. USTs on a currency-hedged basis for USD investors. "
                    "EM Local Currency: Selective overweight — Brazil (SELIC 10.75 %, "
                    "real yield +6 %), India (10yr at 6.85 %), Indonesia (7.25 %) "
                    "offer compelling risk-adjusted returns where current accounts are "
                    "in surplus and FX reserves are ample."
                ),
            },
            {
                "heading": "Duration Positioning",
                "body": (
                    "Benchmark duration 6.5 years for core fixed income allocations. "
                    "Barbell: overweight 2-year (rate-cut optionality) and 30-year "
                    "(institutional demand anchor); underweight 10-year (most exposed "
                    "to term-premium rebuild). TIPS: Neutral — breakevens at 2.40 % "
                    "are fair if core PCE tracks the Fed's 2.3 % 2026 forecast."
                ),
            },
        ],
        "rate_forecasts": {
            "fed_funds_target_eoy":        {"value": "4.00–4.25%", "direction": "hold"},
            "us_10yr_treasury_6m":         {"value": "4.35%",      "direction": "range-bound"},
            "us_10yr_treasury_12m":        {"value": "4.50%",      "direction": "slightly higher"},
            "ig_credit_spread_oas_6m_bp":  {"value": 90,           "direction": "slight widening"},
            "hy_credit_spread_oas_6m_bp":  {"value": 340,          "direction": "modest widening"},
        },
        "as_of_date": "2026-06-17",
    },

    "alternatives": {
        "topic": "alternatives",
        "title": "Meridian Alternatives Strategy — 2026 Playbook",
        "published_date": "2026-06-15",
        "classification": "internal",
        "sections": [
            {
                "heading": "Private Credit",
                "body": (
                    "Our highest-conviction alternatives call for 2026. Senior secured "
                    "direct lending is yielding 10.5 %–12.0 % net of fees on a "
                    "floating-rate basis (SOFR + 550–700 bp). Default rates for "
                    "Meridian's direct-lending book are tracking at 1.6 % (LTM), "
                    "below the market average of 2.2 %. We favour defensive sectors: "
                    "healthcare services, software, and business services. New vintage "
                    "allocations should be directed to 2026–2027 deployment given "
                    "the attractive entry yield environment. Target net IRR: 11–13 %."
                ),
            },
            {
                "heading": "Infrastructure",
                "body": (
                    "Overweight. The energy-transition capex supercycle (grid upgrades, "
                    "offshore wind, battery storage) and AI-driven data-centre demand are "
                    "creating 10–15 year contracted cash-flow opportunities. Meridian's "
                    "infra sleeve targets assets with CPI-linked revenues: UK water "
                    "utilities (OFWAT periodic review completed), Spanish toll roads, "
                    "and US solar merchant-plus-PPA hybrids. Target net IRR: 10–12 %; "
                    "leverage 40–50 % at project level."
                ),
            },
            {
                "heading": "Real Estate",
                "body": (
                    "Neutral overall — bifurcated market. "
                    "Underweight: Office (global vacancy 17.5 %; US CBD class-B being "
                    "converted or razed — structural demand destruction, not cyclical). "
                    "Neutral: Retail (grocery-anchored and necessity-based outperform; "
                    "malls bifurcating between luxury-anchor trophy and distressed). "
                    "Overweight within real estate: Industrial/Logistics (last-mile "
                    "demand resilient; rent growth +5 % YoY), Data Centres "
                    "(demand exceeds supply through 2028), Senior Housing (demographic "
                    "tailwind, occupancy recovering to pre-COVID levels)."
                ),
            },
            {
                "heading": "Hedge Funds",
                "body": (
                    "Neutral overall. Macro discretionary: Overweight — higher macro "
                    "dispersion rewarding tactical rate, FX, and commodity calls; "
                    "avg. net return +9.2 % YTD. Long/Short Equity: Underweight — "
                    "factor crowding and correlation compression limiting alpha "
                    "generation; HF net exposure at 65th percentile historically. "
                    "Merger Arbitrage: Overweight — deal spread widened post-election; "
                    "regulatory stance on large-cap M&A more accommodating in 2026."
                ),
            },
        ],
        "target_allocations": {
            "private_credit":     {"target_pct": 35, "net_irr_target": "11–13%"},
            "infrastructure":     {"target_pct": 30, "net_irr_target": "10–12%"},
            "real_estate":        {"target_pct": 20, "net_irr_target": "8–10%"},
            "hedge_funds":        {"target_pct": 15, "net_irr_target": "7–9%"},
        },
        "as_of_date": "2026-06-15",
    },

    "macro": {
        "topic": "macro",
        "title": "Meridian Global Macro Outlook — H2 2026",
        "published_date": "2026-06-19",
        "classification": "internal",
        "sections": [
            {
                "heading": "US Economy",
                "body": (
                    "Base case: Soft landing. 2026 GDP growth revised to 1.8 % from "
                    "2.1 % at the start of the year. Labour market is loosening — "
                    "unemployment at 4.2 % (up from 3.9 % in Jan), but no spike "
                    "consistent with a hard landing. Core PCE inflation at 2.7 % YoY "
                    "(May 2026); services inflation remains sticky at 3.8 %. "
                    "Fed dot plot median: one additional 25 bp cut in December 2026, "
                    "then a hold through mid-2027. Consumer is resilient but the "
                    "savings cushion built during COVID is now fully depleted for the "
                    "bottom-two income quintiles."
                ),
            },
            {
                "heading": "Europe",
                "body": (
                    "Eurozone GDP growth upgraded to 1.1 % (from 0.8 %) on the back "
                    "of a mild winter, improved energy security, and ECB rate relief "
                    "(deposit facility at 2.50 %, down from 4.00 % in mid-2024). "
                    "Germany remains the laggard at +0.4 % growth; France and Spain "
                    "outperforming at +1.4 % and +2.2 % respectively. Inflation: HICP "
                    "at 2.1 %, near target; ECB is likely done for this cycle. "
                    "Political risk: French coalition fragility and Bundestag elections "
                    "in September are near-term sources of EUR/credit volatility."
                ),
            },
            {
                "heading": "China and Emerging Markets",
                "body": (
                    "China: Official GDP 4.9 % in 2025, with a targeted 5.0 % for 2026. "
                    "Our economists are sceptical — property sector balance-sheet "
                    "restructuring will suppress investment for another 2–3 years. "
                    "Consumer confidence index at 86 (below-neutral); exports resilient "
                    "but facing new tariffs in EU and South-East Asia. "
                    "India: Standout EM story at 6.8 % GDP growth. Manufacturing FDI "
                    "inflows running at $64 bn annualised. RBI on hold at 6.50 %; "
                    "inflation benign at 4.1 %. "
                    "Brazil: Growth 2.4 %; fiscal reform credibility improving; "
                    "BRL has appreciated 6 % YTD vs. USD."
                ),
            },
            {
                "heading": "Macro Tail Risks for H2 2026",
                "body": (
                    "1. Inflation re-acceleration: Services CPI sticky at 3.8 %; "
                    "any upside energy-price shock could force the Fed to abandon "
                    "its easing bias (low probability, high impact). "
                    "2. US fiscal deterioration: Debt ceiling was suspended through "
                    "March 2027; bond-market vigilante risk if deficit tracking "
                    "exceeds 6 % of GDP (currently 5.7 %). "
                    "3. China hard landing: A disorderly property-developer default "
                    "wave in H2 2026 would spill into bank capital, slowing global "
                    "trade by an estimated 0.8 pp (low probability, medium impact). "
                    "4. Middle East escalation: Renewed conflict in the Strait of "
                    "Hormuz could push Brent above $95/bbl, adding 40–60 bp to "
                    "headline inflation globally."
                ),
            },
        ],
        "gdp_forecasts": {
            "US":        {"2026_pct": 1.8, "2027_pct": 2.1},
            "Eurozone":  {"2026_pct": 1.1, "2027_pct": 1.5},
            "UK":        {"2026_pct": 1.3, "2027_pct": 1.8},
            "China":     {"2026_pct": 4.7, "2027_pct": 4.4},
            "India":     {"2026_pct": 6.8, "2027_pct": 6.5},
            "Japan":     {"2026_pct": 1.0, "2027_pct": 1.2},
        },
        "as_of_date": "2026-06-19",
    },
}

VALID_TOPICS = {"equities", "fixed_income", "alternatives", "macro"}

# Brief summary used when the gateway asks for a topic index or an unknown topic is requested
TOPICS_SUMMARY = {
    "equities":      "US and international equity sector ratings, valuation views, and risk factors",
    "fixed_income":  "Rates outlook, credit strategy (IG/HY/EM), and duration positioning",
    "alternatives":  "Private credit, infrastructure, real estate, and hedge fund strategy",
    "macro":         "US, Europe, China, and EM GDP forecasts plus H2 2026 tail risks",
}
