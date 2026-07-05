"""
Canned HR policy content for the HR Policy Q&A agent.

Covers the key policy areas employees and managers ask about:
  - parental_leave:   maternity, paternity, adoption leave entitlements
  - pto:              PTO accrual, carry-over, sabbatical
  - benefits:         health insurance, 401(k), wellness stipend
  - conduct:          code of conduct, ethics hotline, disciplinary process
  - performance:      performance review cadence, PIP process
  - learning:         L&D budget, tuition reimbursement, certifications

All policy details are FICTIONAL demo content for Meridian Demo Bank and must
not be construed as legal advice or real HR policy.
"""

HR_POLICIES: dict = {
    "parental_leave": {
        "topic": "parental_leave",
        "policy_id": "HR-POL-001",
        "title": "Parental Leave Policy",
        "effective_date": "2026-01-01",
        "last_reviewed": "2025-11-15",
        "owner": "People & Culture",
        "sections": [
            {
                "heading": "Maternity Leave",
                "body": (
                    "Eligible employees are entitled to 20 weeks of fully paid maternity "
                    "leave, beginning up to 4 weeks before the expected due date. "
                    "Eligibility: employed for at least 6 months at the commencement of "
                    "leave. Pay is calculated at 100 % of base salary for weeks 1–20. "
                    "Employees may elect an additional 12 weeks of unpaid leave under the "
                    "Family and Medical Leave Act (FMLA). Notification: at least 8 weeks "
                    "prior notice is requested where practicable."
                ),
            },
            {
                "heading": "Paternity / Secondary Carer Leave",
                "body": (
                    "Non-birthing parents (including same-sex partners and co-parents) "
                    "are entitled to 10 weeks of fully paid paternity/secondary-carer "
                    "leave, to be taken within 12 months of the birth or adoption of a child. "
                    "Leave may be taken in blocks of no fewer than 2 weeks. Additional "
                    "unpaid FMLA leave applies after the 10-week paid period."
                ),
            },
            {
                "heading": "Adoption Leave",
                "body": (
                    "Employees adopting a child under 16 are entitled to 16 weeks of "
                    "fully paid adoption leave (primary adopter) or 10 weeks (secondary "
                    "adopter), commencing from the date of placement. Foster-to-adopt "
                    "arrangements qualify from the date of initial placement for the "
                    "primary carer."
                ),
            },
            {
                "heading": "Return-to-Work",
                "body": (
                    "The firm guarantees return to the same role and band on completion "
                    "of parental leave. If the original role has been made redundant, "
                    "the employee will be offered a suitable alternative. A phased return "
                    "(4-day week for the first 4 weeks) is available on request. "
                    "Keeping-in-Touch (KIT) days: up to 10 paid days during leave are "
                    "permitted by mutual agreement."
                ),
            },
        ],
        "key_facts": {
            "maternity_leave_weeks_paid": 20,
            "paternity_leave_weeks_paid": 10,
            "adoption_primary_weeks_paid": 16,
            "adoption_secondary_weeks_paid": 10,
            "kit_days": 10,
            "eligibility_months": 6,
        },
    },

    "pto": {
        "topic": "pto",
        "policy_id": "HR-POL-002",
        "title": "Paid Time Off (PTO) and Leave Policy",
        "effective_date": "2026-01-01",
        "last_reviewed": "2025-11-15",
        "owner": "People & Culture",
        "sections": [
            {
                "heading": "Annual PTO Accrual",
                "body": (
                    "PTO accrues on a per-pay-period basis from the first day of employment. "
                    "Year 1–3: 20 days per year (1.54 days/bi-weekly period). "
                    "Year 4–7: 25 days per year (1.92 days/bi-weekly period). "
                    "Year 8+:  30 days per year (2.31 days/bi-weekly period). "
                    "Managing Director and above: 30 days from date of hire. "
                    "Part-time employees accrue pro-rata based on scheduled hours."
                ),
            },
            {
                "heading": "Carry-Over and PTO Cash-Out",
                "body": (
                    "Unused PTO may be carried over up to a maximum of 10 days (one "
                    "calendar year) into the following year. PTO in excess of the carry-"
                    "over maximum is forfeited on 31 December unless a written exception "
                    "is approved by the employee's manager and HR Business Partner. "
                    "Voluntary cash-out: employees may elect to cash out up to 5 accrued "
                    "PTO days per year during the October enrollment window, paid at base "
                    "salary rate in the December payroll."
                ),
            },
            {
                "heading": "Sick Leave",
                "body": (
                    "Sick leave is separate from PTO. Employees receive 10 sick days per "
                    "calendar year, non-accruing and non-carrying-over. Sick leave is not "
                    "paid out on termination. Sick leave may be used for the employee's "
                    "own illness, for caring for a family member under applicable state law, "
                    "or for medical/dental appointments."
                ),
            },
            {
                "heading": "Sabbatical Program",
                "body": (
                    "Employees with 7+ years of continuous service are eligible for a "
                    "paid sabbatical of up to 4 weeks, once every 5 years. Sabbaticals "
                    "are intended for personal development, community service, or extended "
                    "travel. A sabbatical plan must be submitted and approved by the "
                    "employee's Managing Director at least 3 months in advance."
                ),
            },
            {
                "heading": "Public Holidays",
                "body": (
                    "Meridian observes 11 federal public holidays. Employees required "
                    "to work on a public holiday receive time-and-a-half pay plus a "
                    "compensatory day off to be taken within 90 days."
                ),
            },
        ],
        "key_facts": {
            "pto_year1_3_days": 20,
            "pto_year4_7_days": 25,
            "pto_year8_plus_days": 30,
            "carry_over_max_days": 10,
            "cash_out_max_days": 5,
            "sick_days_per_year": 10,
            "sabbatical_eligibility_years": 7,
            "sabbatical_duration_weeks": 4,
            "public_holidays": 11,
        },
    },

    "benefits": {
        "topic": "benefits",
        "policy_id": "HR-POL-003",
        "title": "Employee Benefits Summary",
        "effective_date": "2026-01-01",
        "last_reviewed": "2025-11-15",
        "owner": "Total Rewards",
        "sections": [
            {
                "heading": "Health Insurance",
                "body": (
                    "Meridian offers three medical plan tiers through Aetna: "
                    "(1) High-Deductible Health Plan (HDHP) with Health Savings Account "
                    "(HSA) — Meridian contributes $1,500 (employee) or $3,000 (family) "
                    "to the HSA annually. "
                    "(2) PPO Standard — Meridian covers 80 % of premiums; employee covers "
                    "20 %. "
                    "(3) PPO Plus — Meridian covers 70 % of premiums; employee covers "
                    "30 %; lower out-of-pocket maximums ($2,000 individual / $4,000 family). "
                    "Dental (MetLife): basic preventative 100 % covered, major restorative "
                    "60 %. Vision (VSP): annual eye exam + $200 frame/contact allowance "
                    "covered in full."
                ),
            },
            {
                "heading": "401(k) Retirement Plan",
                "body": (
                    "Meridian matches 100 % of employee contributions up to 6 % of eligible "
                    "compensation (4-year cliff vesting for employer match). The plan is "
                    "administered by Fidelity Investments. 2026 IRS contribution limit: "
                    "$23,500 (under 50) / $31,000 (50+ catch-up). A Roth 401(k) option "
                    "is available. Auto-enrolment at 4 % contribution rate applies to "
                    "new hires unless the employee opts out within 30 days. "
                    "Financial wellness: all employees have access to a Fidelity financial "
                    "planner for one free session per year."
                ),
            },
            {
                "heading": "Wellness and Mental Health",
                "body": (
                    "Wellness Stipend: $1,200 per year (reimbursable: gym membership, "
                    "fitness equipment, nutrition coaching, ergonomic home-office equipment). "
                    "Employee Assistance Programme (EAP): 8 free counselling sessions "
                    "per year with a licensed therapist (confidential, via Spring Health). "
                    "Mental health: psychiatric visits covered at the same co-pay rate as "
                    "primary care ($25 per visit, PPO plans). "
                    "On-site/virtual yoga and mindfulness classes: 3 sessions per week."
                ),
            },
            {
                "heading": "Life and Disability Insurance",
                "body": (
                    "Group Life Insurance: Meridian provides 2x base salary, up to $500,000 "
                    "(no employee premium). Optional supplemental life insurance: up to 5x "
                    "salary at employee cost. "
                    "Short-Term Disability (STD): 80 % of base salary for up to 12 weeks "
                    "after a 7-day waiting period. "
                    "Long-Term Disability (LTD): 60 % of base salary to age 65 (90-day "
                    "elimination period) — Meridian-funded."
                ),
            },
            {
                "heading": "Other Perks",
                "body": (
                    "Commuter Benefits: Pre-tax transit/parking up to $315/month (IRS limit). "
                    "Backup Care (Bright Horizons): 20 backup care days per year (child or "
                    "adult dependent). "
                    "Tuition Reimbursement: $10,000 per year for eligible degree/certificate "
                    "programmes (see Learning & Development Policy HR-POL-006). "
                    "Employee Stock Purchase Plan (ESPP): 10 % discount on Meridian shares, "
                    "offered twice a year (enrolment in January and July)."
                ),
            },
        ],
        "key_facts": {
            "k401_employer_match_pct": 6,
            "k401_vesting_years": 4,
            "k401_2026_limit_under50": 23500,
            "hsa_contribution_employee": 1500,
            "hsa_contribution_family": 3000,
            "wellness_stipend_annual": 1200,
            "eap_sessions_per_year": 8,
            "life_insurance_multiple": "2x base salary",
            "std_coverage_pct": 80,
            "ltd_coverage_pct": 60,
            "tuition_reimbursement_annual": 10000,
            "backup_care_days": 20,
        },
    },

    "conduct": {
        "topic": "conduct",
        "policy_id": "HR-POL-004",
        "title": "Code of Conduct and Ethics Policy",
        "effective_date": "2026-01-01",
        "last_reviewed": "2025-11-15",
        "owner": "Legal & Compliance",
        "sections": [
            {
                "heading": "Core Principles",
                "body": (
                    "Meridian's Code of Conduct is built on four principles: "
                    "(1) Integrity — act honestly and transparently in all dealings "
                    "with clients, colleagues, and regulators. "
                    "(2) Respect — maintain a workplace free from discrimination, "
                    "harassment, and bullying. Meridian is an equal opportunity employer. "
                    "(3) Confidentiality — protect client, employee, and firm information "
                    "in accordance with applicable laws and internal data-classification "
                    "standards. "
                    "(4) Accountability — take ownership of decisions and their consequences; "
                    "speak up when you see a problem."
                ),
            },
            {
                "heading": "Conflicts of Interest",
                "body": (
                    "Employees must disclose potential conflicts of interest to Compliance "
                    "promptly. This includes: outside business activities (board seats, "
                    "advisory roles), personal account dealing in securities the firm "
                    "trades or advises on (pre-clearance required via ComplySci), gifts "
                    "and entertainment above $250 in value (approval required), and "
                    "relationships with vendors or clients that could influence decisions. "
                    "The firm maintains a Restricted List and a Watch List; trading "
                    "restrictions apply to all employees for securities on these lists."
                ),
            },
            {
                "heading": "Ethics Hotline and Reporting",
                "body": (
                    "Suspected violations of law, regulation, or firm policy should be "
                    "reported via one of the following channels: "
                    "(1) Ethics Hotline: 1-800-MER-ETHX (anonymous, 24/7, operated by "
                    "an independent third party). "
                    "(2) Compliance email: compliance@meridian.demo (monitored daily). "
                    "(3) Direct report to Compliance Officer or General Counsel. "
                    "Meridian prohibits retaliation against any employee who raises "
                    "a good-faith concern. Reports are treated as confidential to the "
                    "extent permitted by law."
                ),
            },
            {
                "heading": "Disciplinary Process",
                "body": (
                    "Confirmed breaches of the Code of Conduct are subject to a graduated "
                    "disciplinary response: "
                    "(1) Verbal counselling — for minor first-time infractions. "
                    "(2) Written warning — documented in the employee's personnel file. "
                    "(3) Final written warning / Performance Improvement Plan (PIP). "
                    "(4) Termination for cause — for serious breaches (fraud, harassment, "
                    "insider trading, gross misconduct). "
                    "Egregious violations (e.g. financial crime, sexual harassment) may "
                    "result in immediate termination without prior warning. All investigations "
                    "follow Meridian's Workplace Investigation Procedure (HR-PROC-012)."
                ),
            },
            {
                "heading": "Social Media and External Communications",
                "body": (
                    "Employees may use personal social media but must not disclose "
                    "confidential client or firm information, comment on the firm's "
                    "financial performance, or make statements that could be attributed "
                    "to Meridian without explicit Corporate Communications approval. "
                    "Regulated roles (investment professionals, research analysts) must "
                    "pre-approve all external publications and public appearances with "
                    "Compliance."
                ),
            },
        ],
        "key_facts": {
            "gift_approval_threshold_usd": 250,
            "ethics_hotline": "1-800-MER-ETHX",
            "compliance_email": "compliance@meridian.demo",
            "retaliation_prohibited": True,
        },
    },

    "performance": {
        "topic": "performance",
        "policy_id": "HR-POL-005",
        "title": "Performance Management Policy",
        "effective_date": "2026-01-01",
        "last_reviewed": "2025-11-15",
        "owner": "People & Culture",
        "sections": [
            {
                "heading": "Review Cadence",
                "body": (
                    "Meridian operates a continuous performance model with three formal "
                    "touchpoints per year: "
                    "(1) Mid-year check-in (June) — goal progress review, development "
                    "conversation, no rating assigned. "
                    "(2) Year-end review (November–December) — full performance assessment "
                    "against agreed objectives, behaviours, and firm values. Formal rating "
                    "assigned on a 5-point scale. "
                    "(3) Compensation calibration (January) — ratings inform the annual "
                    "bonus and merit increase decisions."
                ),
            },
            {
                "heading": "Rating Scale",
                "body": (
                    "5 – Exceptional: Consistently exceeds all objectives; role model "
                    "for the firm's values. Top 5 % of performers. "
                    "4 – Exceeds Expectations: Consistently exceeds most objectives. "
                    "Top 20 % of performers. "
                    "3 – Meets Expectations: Meets all objectives; strong contributor. "
                    "Core 55 % of performers. "
                    "2 – Partially Meets: Meets some objectives; development required in "
                    "key areas. "
                    "1 – Does Not Meet: Falls short of objectives; PIP or managed exit."
                ),
            },
            {
                "heading": "Performance Improvement Plan (PIP)",
                "body": (
                    "A PIP is triggered for employees rated 1 (Does Not Meet) or where "
                    "sustained performance concerns have been documented. A PIP runs for "
                    "60–90 days and includes: measurable objectives, bi-weekly check-ins "
                    "with manager and HR Business Partner, support resources (coaching, "
                    "training). Failure to meet PIP objectives may result in role change "
                    "or termination. A completed PIP does not preclude future advancement "
                    "if subsequent performance is strong."
                ),
            },
            {
                "heading": "360-Degree Feedback",
                "body": (
                    "As part of the year-end review, employees at VP and above receive "
                    "structured 360-degree feedback from peers, direct reports, and "
                    "cross-functional partners. Feedback is confidential and synthesised "
                    "by the HR Business Partner before sharing with the manager and "
                    "employee. 360 results are developmental inputs — they do not "
                    "override the manager's formal rating."
                ),
            },
        ],
        "key_facts": {
            "review_cycles_per_year": 3,
            "rating_scale_min": 1,
            "rating_scale_max": 5,
            "pip_duration_days": "60–90",
            "360_feedback_from_vp": True,
        },
    },

    "learning": {
        "topic": "learning",
        "policy_id": "HR-POL-006",
        "title": "Learning and Development Policy",
        "effective_date": "2026-01-01",
        "last_reviewed": "2025-11-15",
        "owner": "People & Culture",
        "sections": [
            {
                "heading": "Annual L&D Budget",
                "body": (
                    "Every employee has an annual Learning & Development budget of $3,000 "
                    "(Analyst to VP) or $5,000 (Director and above). The budget covers: "
                    "conference attendance, professional certification fees and study "
                    "materials, workshop registrations, and online learning platform "
                    "subscriptions (Coursera, LinkedIn Learning, O'Reilly). Budget is "
                    "non-accumulating (use it or lose it by 31 December); expense via "
                    "Concur under cost centre GL-L&D."
                ),
            },
            {
                "heading": "Tuition Reimbursement",
                "body": (
                    "Meridian reimburses up to $10,000 per year for accredited degree "
                    "programmes (MBA, MSc, BSc) or professional certificates (CFA, CPA, "
                    "FRM, CAIA, CISSP, etc.). Eligibility: 1 year of service; employment "
                    "must continue for 1 year post-completion (pro-rata clawback applies). "
                    "Approval by manager and HR BP required before enrolment. Reimbursement "
                    "is paid after submission of grades (B or above required for degree "
                    "programmes)."
                ),
            },
            {
                "heading": "Meridian Academy",
                "body": (
                    "Meridian Academy delivers internal mandatory and elective training: "
                    "Mandatory: Anti-Money Laundering (AML) recertification (annual), "
                    "Information Security Awareness (annual), Code of Conduct attestation "
                    "(annual), Unconscious Bias training (bi-annual). "
                    "Elective: Leadership Essentials (for first-time managers), Excel & "
                    "Power BI for Finance, CFA Exam Prep (subsidised), Presentation & "
                    "Negotiation Skills. All mandatory training must be completed by "
                    "31 March each year to avoid a flag on the employee's performance review."
                ),
            },
            {
                "heading": "Mentoring and Coaching",
                "body": (
                    "Structured mentoring programme: Analyst and Associate employees are "
                    "matched with a VP or above mentor for a 9-month engagement. Monthly "
                    "1-hour sessions; a development plan is jointly agreed in month 1. "
                    "Executive coaching: Directors and above may request up to 6 sessions "
                    "per year with an approved external executive coach (charged to "
                    "departmental L&D budget, not personal allowance)."
                ),
            },
        ],
        "key_facts": {
            "ld_budget_analyst_to_vp_usd": 3000,
            "ld_budget_director_plus_usd": 5000,
            "tuition_reimbursement_annual_usd": 10000,
            "tuition_grade_requirement": "B or above",
            "mandatory_training_deadline": "31 March",
            "mentoring_engagement_months": 9,
            "executive_coaching_sessions": 6,
        },
    },
}

VALID_TOPICS = set(HR_POLICIES.keys())

# Brief summary used when the gateway asks for a topic index
TOPICS_SUMMARY = {
    "parental_leave": "Maternity (20 wks paid), paternity (10 wks paid), and adoption leave",
    "pto":            "PTO accrual (20–30 days), carry-over (10 days max), sick leave (10 days/yr)",
    "benefits":       "Health (Aetna PPO/HDHP), 401(k) 6 % match, $1,200 wellness stipend",
    "conduct":        "Code of Conduct, conflict-of-interest rules, ethics hotline, disciplinary steps",
    "performance":    "Annual review cycle (3 touchpoints), 5-point rating scale, PIP process",
    "learning":       "$3,000–$5,000 L&D budget, $10,000 tuition reimbursement, Meridian Academy",
}
