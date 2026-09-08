package com.discoveryhub.tools.corpus;

import java.util.List;

/**
 * Source text for the background corpus. Nothing here is random at runtime — the generator picks
 * from these pools with a seeded RNG, so the same seed always yields the same prose.
 */
final class Vocabulary {

    private Vocabulary() {
    }

    /** External counterparties, so not every message is internal-only. */
    static final String[] EXTERNAL_CONTACTS = {
            "d.iverson@northgate-transit.gov",
            "procurement@northgate-transit.gov",
            "s.mallory@bridgeline-systems.com",
            "contracts@harrowgate-legal.com",
            "a.petrossian@caldwell-audit.com",
            "support@veritas-cloud.io",
            "j.okafor@stonebridge-capital.com",
            "invoices@atlas-fabrication.com",
    };

    record Topic(String id, String department, String subject, String[] sentences,
                 String[] attachments, double attachmentBias, boolean externalLikely) {
    }

    static final List<Topic> TOPICS = List.of(
            new Topic("q-close", "FINANCE", "Q%d close — outstanding items",
                    new String[]{
                            "We are still missing the accrual detail for the field services line.",
                            "Controller review is scheduled for Thursday; anything unreconciled by Wednesday noon slips to next period.",
                            "Two intercompany entries reversed incorrectly and I have re-posted them.",
                            "The variance against forecast is 4.2 percent, driven almost entirely by contractor spend.",
                            "Please confirm the revenue recognition treatment before we lock the ledger.",
                            "I have attached the reconciliation pack for your sign-off.",
                    },
                    new String[]{"reconciliation-pack.csv", "accrual-detail.csv", "variance-summary.csv"},
                    0.35, false),
            new Topic("invoice", "FINANCE", "Invoice %d — payment terms",
                    new String[]{
                            "The vendor is disputing the net-45 terms we agreed in the master services agreement.",
                            "Purchase order does not match the line items billed; the difference is 8,400 dollars.",
                            "I have put this on hold pending confirmation from procurement.",
                            "Accounts payable will release the payment once the receipt is matched.",
                            "Please do not approve this until legal has reviewed the amended schedule.",
                    },
                    new String[]{"invoice.csv", "purchase-order.csv"},
                    0.45, true),
            new Topic("budget", "FINANCE", "FY budget planning — %s department",
                    new String[]{
                            "Headcount assumptions need to be locked before we circulate this to the board.",
                            "The capital request for the fabrication line was trimmed by fifteen percent.",
                            "I would rather defer the tooling spend than cut the maintenance budget.",
                            "Finance will need the department submissions by the end of the month.",
                    },
                    new String[]{"budget-draft.csv"},
                    0.30, false),
            new Topic("incident", "ENGINEERING", "INC-%d — degraded ingest throughput",
                    new String[]{
                            "Consumer lag on the primary topic climbed to eleven minutes before we shed load.",
                            "Root cause looks like a hot partition; the key distribution is badly skewed.",
                            "We failed over to the standby cluster at 02:14 and the backlog drained in forty minutes.",
                            "No data loss. Every record was replayed from the log after recovery.",
                            "I am writing the postmortem now and will circulate it for review tomorrow.",
                            "Adding an alert on consumer lag so we catch this before customers do.",
                    },
                    new String[]{"incident-timeline.csv", "postmortem.txt"},
                    0.25, false),
            new Topic("release", "ENGINEERING", "Release %d.%d — go / no-go",
                    new String[]{
                            "All blocking defects are closed; two cosmetic issues are deferred to the next train.",
                            "QA signed off on the regression suite this morning.",
                            "The migration is backwards compatible, so we can roll back within the window if needed.",
                            "Please hold non-essential deploys until the release lands.",
                            "Marking this go from my side.",
                    },
                    new String[]{"release-notes.txt", "test-summary.csv"},
                    0.20, false),
            new Topic("architecture", "ENGINEERING", "Design review — %s",
                    new String[]{
                            "I am not convinced the shared schema survives contact with a second team.",
                            "Let us keep the write path synchronous and push everything else onto the event stream.",
                            "The retry semantics need to be idempotent or we will duplicate records on replay.",
                            "Latency budget is two seconds end to end; the current design spends most of it in the join.",
                            "Documented the trade-off as an ADR so we stop relitigating it.",
                    },
                    new String[]{"design-notes.txt"},
                    0.15, false),
            new Topic("security", "ENGINEERING", "Access review — %s systems",
                    new String[]{
                            "Nine accounts still have production access after leaving the team.",
                            "Rotating the service credentials this weekend; expect a brief authentication blip.",
                            "The audit found no evidence of unauthorised access, only stale entitlements.",
                            "Please complete your quarterly attestation by Friday.",
                    },
                    new String[]{"access-review.csv"},
                    0.40, false),
            new Topic("rfp", "SALES", "%s RFP — response draft",
                    new String[]{
                            "The submission window closes at 17:00 local time and they will not accept late filings.",
                            "Our differentiator has to be the service level commitment, not the price.",
                            "I have redlined the compliance matrix; two requirements we cannot meet as written.",
                            "Procurement asked for three reference customers in the same sector.",
                            "Draft is attached — comments by Wednesday please.",
                    },
                    new String[]{"rfp-response-draft.txt", "compliance-matrix.csv", "pricing-sheet.csv"},
                    0.45, true),
            new Topic("pipeline", "SALES", "Pipeline review — week %d",
                    new String[]{
                            "Two deals slipped out of the quarter; both are procurement delays, not losses.",
                            "The forecast is 82 percent covered, which is thin for this point in the cycle.",
                            "I need engineering time for the proof of concept or we will lose the evaluation.",
                            "Renewal is at risk; the sponsor left and the new one is running a competitive process.",
                    },
                    new String[]{"pipeline-export.csv"},
                    0.25, false),
            new Topic("customer", "SALES", "%s — escalation",
                    new String[]{
                            "The customer is unhappy with the response time on their last three tickets.",
                            "I have committed to a written remediation plan by end of week.",
                            "Please do not promise a date until engineering confirms it.",
                            "They have asked for a credit against the current invoice.",
                    },
                    new String[]{"remediation-plan.txt"},
                    0.20, true),
            new Topic("contract", "LEGAL", "Contract review — %s agreement",
                    new String[]{
                            "The indemnity cap is uncapped for data breach, which we cannot accept.",
                            "I have proposed mutual limitation of liability at twelve months of fees.",
                            "Governing law stays with us; that is not negotiable at this deal size.",
                            "Please route the executed copy to records once it is countersigned.",
                            "Flagging this as privileged — do not forward outside the review group.",
                    },
                    new String[]{"redline.txt", "term-sheet.txt"},
                    0.40, true),
            new Topic("policy", "LEGAL", "Retention policy — %s records",
                    new String[]{
                            "Default retention for operational correspondence is seven years from creation.",
                            "Anything within the scope of an active hold is exempt from disposition, without exception.",
                            "We need an auditable record of what was deleted and on whose authority.",
                            "Records management will run the disposition report at the end of each quarter.",
                    },
                    new String[]{"retention-schedule.csv"},
                    0.30, false),
            new Topic("logistics", "OPERATIONS", "Depot %d — schedule change",
                    new String[]{
                            "The delivery window moved to Tuesday because the crane hire fell through.",
                            "Two units arrived with transit damage and have been quarantined pending inspection.",
                            "Site access needs to be arranged with the municipal authority forty-eight hours ahead.",
                            "Crew is on site from 06:00; please confirm the gate code.",
                    },
                    new String[]{"delivery-schedule.csv", "inspection-report.txt"},
                    0.30, true),
            new Topic("maintenance", "OPERATIONS", "Preventive maintenance — %s fleet",
                    new String[]{
                            "Three vehicles are overdue for their scheduled inspection.",
                            "Parts lead time has stretched to six weeks, so I am ordering ahead of the cycle.",
                            "Downtime should be under four hours per unit if we stage the work overnight.",
                    },
                    new String[]{"maintenance-log.csv"},
                    0.25, false),
            new Topic("onboarding", "HR", "Onboarding — new starters, %s",
                    new String[]{
                            "Four starters next Monday; equipment is ordered and accounts are provisioned.",
                            "Please schedule the compliance training within the first two weeks.",
                            "The offer letter template has changed, use the version in the shared drive.",
                    },
                    new String[]{"onboarding-checklist.csv"},
                    0.20, false),
            new Topic("review-cycle", "HR", "Performance cycle — calibration",
                    new String[]{
                            "Calibration sessions run through the last two weeks of the month.",
                            "Please have draft ratings entered before your session.",
                            "Keep written feedback factual and specific; it may be disclosable.",
                    },
                    new String[]{},
                    0.05, false),
            new Topic("board", "EXECUTIVE", "Board pack — %s",
                    new String[]{
                            "The board wants a single slide on the public sector pipeline and nothing more.",
                            "Please keep commentary out of the appendix; the numbers should speak for themselves.",
                            "We will take the capital request as a separate item after the operating review.",
                            "Circulating this on Friday, so comments by Thursday evening.",
                    },
                    new String[]{"board-pack-outline.txt", "kpi-summary.csv"},
                    0.35, false),
            new Topic("allhands", "EXECUTIVE", "All-hands — %s agenda",
                    new String[]{
                            "Thirty minutes, three topics, and time for questions at the end.",
                            "I want the operations team to present the depot turnaround work themselves.",
                            "Recording will be posted for anyone who cannot attend live.",
                    },
                    new String[]{},
                    0.05, false)
    );

    static final String[] SUBJECT_FILLERS = {
            "Northgate", "Bridgeline", "Atlas Fabrication", "Stonebridge", "Caldwell",
            "platform", "field services", "transit", "eastern region", "western depot",
            "January", "February", "March", "April", "May", "June", "July", "August",
            "September", "October", "November", "December",
    };

    static final String[] GREETINGS = {
            "Hi %s,", "%s,", "Morning %s,", "Thanks %s,", "Hi all,", "Team,", "Quick one, %s —",
    };

    static final String[] CLOSINGS = {
            "Thanks,", "Best,", "Regards,", "Cheers,", "Appreciated,", "Talk soon,",
    };

    static final String[] REPLY_OPENERS = {
            "Agreed.", "Understood.", "That works.", "Not quite.", "I disagree, respectfully.",
            "Picking this up.", "Following up on the below.", "One correction.",
            "Adding %s for visibility.", "Looping back on this.",
    };

    static final String[] CHAT_LINES = {
            "have you got five minutes?",
            "the build is red again",
            "can you approve the ticket when you get a sec",
            "in a meeting, will ping after",
            "did procurement come back to us yet",
            "sending you the file now",
            "that number looks wrong to me",
            "no rush, whenever you get to it",
            "call me when you are free",
            "who owns this now that ingrid is out",
            "i will pick it up in the morning",
            "confirmed, it is deployed",
            "can we push the review to thursday",
            "thanks, that unblocks me",
            "heads up, the customer is asking again",
            "i do not think we should put that in writing",
            "on it",
            "the depot gate code changed, use 4471",
            "reminder: quarterly attestation is due friday",
            "did you see the postmortem?",
    };
}
