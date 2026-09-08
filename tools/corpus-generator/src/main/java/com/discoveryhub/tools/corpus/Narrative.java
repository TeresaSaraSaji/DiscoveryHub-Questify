package com.discoveryhub.tools.corpus;

import com.discoveryhub.contracts.Attachment;
import com.discoveryhub.contracts.Custodian;
import com.discoveryhub.contracts.Ids;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The planted storyline the demo walks through: <b>Project Halyard</b>.
 *
 * <p>Meridian Dynamics is bidding for the Northgate transit contract. A small group obtains a
 * competitor's indicative figures through a recent hire, prices just underneath them, wins the
 * award, and afterwards one of them asks for the folder to be "cleaned up". Legal is looped in
 * late and the exchange is marked privileged.
 *
 * <p>This exists so the demo is a story rather than a keyword search over noise. It deliberately
 * exercises: full-text hits on a codename, a privileged thread that must be filterable, a
 * smoking-gun message sent at the weekend, attachments that have to survive export with intact
 * checksums, and the same conversation captured from two different mailboxes.
 */
final class Narrative {

    static final String CODENAME = "Halyard";

    private final Roster roster;
    private final List<Message> messages = new ArrayList<>();
    private final Map<String, String> lastInThread = new LinkedHashMap<>();
    private int sequence = 0;

    private Narrative(Roster roster) {
        this.roster = roster;
    }

    static List<Message> build(Roster roster) {
        Narrative n = new Narrative(roster);
        n.compose();
        return List.copyOf(n.messages);
    }

    private void compose() {
        // --- Thread 1: legitimate bid preparation -----------------------------------------
        String t1 = "northgate-rfp-2024";
        add(t1, "dana", List.of("marcus", "priya"), List.of(), t("2024-02-06T09:12:00Z"),
                "Northgate transit RFP — kickoff",
                """
                Marcus, Priya,

                Northgate published the transit maintenance RFP this morning. Submission closes
                29 March at 17:00 and they will not accept late filings. This is the largest
                public sector opportunity in our pipeline this year.

                Marcus owns the response, Priya owns the pricing model. I want a first pass at
                both by the end of next week.

                Thanks,
                Dana""", List.of());

        add(t1, "priya", List.of("dana", "marcus"), List.of(), t("2024-02-06T14:40:00Z"),
                "Re: Northgate transit RFP — kickoff",
                """
                Understood. Our cost base on a contract this size gives us room down to about
                14.2 million before margin gets uncomfortable. I would not go below that without
                a conversation with Vikram.

                Attaching the first cut of the model.

                Priya""", List.of(),
                List.of(csv("halyard-cost-model-v1.csv",
                        "line,description,cost,margin_pct",
                        "1,Depot maintenance crews,6420000,11.5",
                        "2,Spares and consumables,3180000,9.0",
                        "3,Telemetry platform licence,1450000,22.0",
                        "4,Programme management,980000,18.0",
                        "5,Contingency,2200000,0.0")));

        add(t1, "marcus", List.of("dana"), List.of(), t("2024-02-09T17:55:00Z"),
                "Re: Northgate transit RFP — kickoff",
                """
                Dana,

                Compliance matrix is drafted. Two requirements we cannot meet as written, both
                around on-site response time overnight. I have proposed alternative wording.

                The thing that worries me is price. Bridgeline has held this contract for eleven
                years and they know exactly what it costs to run. We are guessing.

                Marcus""", List.of());

        // --- Thread 2: the codename appears -----------------------------------------------
        String t2 = "halyard-pricing";
        add(t2, "dana", List.of("marcus"), List.of(), t("2024-02-19T20:31:00Z"),
                "Halyard",
                """
                Marcus,

                Let us call the Northgate pricing work Halyard from here on. Keep it off the main
                bid thread and off the shared drive.

                Declan came to us from Bridgeline eight months ago. He was on their transit
                account. Have a quiet word and find out where their number is likely to land.

                Dana""", List.of("SENSITIVE"));

        add(t2, "marcus", List.of("dana"), List.of(), t("2024-02-20T08:05:00Z"),
                "Re: Halyard",
                """
                I spoke to Declan. He still has the indicative figures they used for the last
                renewal and he thinks their uplift assumptions have not changed materially.

                I am not comfortable putting the file on the network. Sending it here only.

                Marcus""", List.of("SENSITIVE"),
                List.of(csv("bridgeline-indicative-figures.csv",
                        "package,bridgeline_2019,uplift_pct,implied_2024",
                        "Depot maintenance,5980000,14.0,6817200",
                        "Spares,2740000,19.5,3274300",
                        "Telemetry,1610000,6.0,1706600",
                        "Programme management,890000,11.0,987900",
                        "TOTAL,11220000,,12786000")));

        add(t2, "dana", List.of("marcus"), List.of(), t("2024-02-20T08:47:00Z"),
                "Re: Halyard",
                """
                That is the whole ballgame. Twelve point eight.

                Get Priya to rebuild the model to land at 12.4 and find the difference in
                contingency and programme management. Do not tell her where the number came from.

                Dana""", List.of("SENSITIVE"));

        // --- Thread 3: the number is engineered -------------------------------------------
        String t3 = "halyard-model-rebuild";
        add(t3, "marcus", List.of("priya"), List.of("dana"), t("2024-02-21T11:20:00Z"),
                "Halyard — revised target",
                """
                Priya,

                Change of plan on Northgate. Dana wants the submission to land at 12.4 million,
                not 14.2. I know that is a long way down.

                Take it out of contingency first and then programme management. If we have to,
                we can revisit the crew ratios in year three.

                Marcus""", List.of());

        add(t3, "priya", List.of("marcus"), List.of("dana"), t("2024-02-21T16:02:00Z"),
                "Re: Halyard — revised target",
                """
                Marcus,

                I can get to 12.4 but I want to be clear about what that means. Contingency goes
                to zero and programme management is funded at roughly two thirds of what the
                delivery plan actually needs. If anything goes wrong in year one there is no
                headroom at all.

                Where did 12.4 come from? It is a very specific number for a target that was
                14.2 two weeks ago.

                Attaching v7 at the revised figure.

                Priya""", List.of(),
                List.of(csv("halyard-pricing-v7.csv",
                        "line,description,cost,note",
                        "1,Depot maintenance crews,6420000,unchanged",
                        "2,Spares and consumables,3180000,unchanged",
                        "3,Telemetry platform licence,1450000,unchanged",
                        "4,Programme management,650000,reduced on instruction",
                        "5,Contingency,0,removed on instruction",
                        "TOTAL,,11700000,before margin",
                        "SUBMISSION,,12400000,")));

        add(t3, "marcus", List.of("priya"), List.of(), t("2024-02-21T16:35:00Z"),
                "Re: Halyard — revised target",
                """
                It came from Dana. I would leave it there.

                Marcus""", List.of());

        // --- Thread 4: privileged legal review --------------------------------------------
        String t4 = "halyard-legal-review";
        add(t4, "owen", List.of("dana", "marcus"), List.of("lena"), t("2024-03-11T10:15:00Z"),
                "PRIVILEGED & CONFIDENTIAL — Northgate submission review",
                """
                Dana, Marcus,

                Privileged and confidential — attorney-client communication. Do not forward.

                I have been asked to review the Northgate submission before filing. Two questions
                I need answered in writing:

                1. What is the basis for the 12.4 million figure? The delivery plan attached to
                   the submission does not reconcile to the priced programme management line.
                2. Has anyone on the bid team had access to competitor pricing information,
                   whether directly or through a former employee of a competitor?

                Please answer both before I sign the certification.

                Owen Castellanos
                General Counsel""", List.of("PRIVILEGED"));

        add(t4, "dana", List.of("owen"), List.of(), t("2024-03-11T13:44:00Z"),
                "Re: PRIVILEGED & CONFIDENTIAL — Northgate submission review",
                """
                Owen,

                The figure is a commercial judgement based on our own cost model and our appetite
                to win this account. No competitor information has been used.

                Dana""", List.of("PRIVILEGED"));

        add(t4, "owen", List.of("dana"), List.of("lena"), t("2024-03-11T15:02:00Z"),
                "Re: PRIVILEGED & CONFIDENTIAL — Northgate submission review",
                """
                Noted, and I will rely on that representation.

                For the avoidance of doubt: if that position changes at any point, the obligation
                to tell me is immediate. Certification of the bid is made on the strength of it.

                Owen""", List.of("PRIVILEGED"));

        // --- Thread 5: the award ------------------------------------------------------------
        String t5 = "northgate-award";
        add(t5, "dana", List.of("hal", "sonia"), List.of("marcus", "priya", "renee"),
                t("2024-04-18T08:30:00Z"),
                "Northgate award — we won",
                """
                Northgate confirmed this morning. Award to Meridian at 12.4 million over four
                years. Bridgeline came in at 12.79.

                Renee will pick up mobilisation from here. Congratulations to the bid team.

                Dana""", List.of());

        add(t5, "renee", List.of("dana", "marcus"), List.of("ingrid"), t("2024-04-18T11:12:00Z"),
                "Re: Northgate award — we won",
                """
                Congratulations all.

                One flag before I build the mobilisation plan: the priced programme management
                line will not cover the structure in the delivery schedule we submitted. I am
                about 340 thousand a year short on my own reading of it.

                Renee""", List.of());

        add(t5, "marcus", List.of("renee"), List.of(), t("2024-04-18T11:48:00Z"),
                "Re: Northgate award — we won",
                """
                Understood. Work to the submitted number for now and we will look at a variation
                once the contract is bedded in.

                Marcus""", List.of());

        // --- Thread 6: the cover-up, sent at the weekend ------------------------------------
        String t6 = "halyard-housekeeping";
        add(t6, "dana", List.of("marcus"), List.of(), t("2024-05-11T21:37:00Z"),
                "Housekeeping",
                """
                Marcus,

                Now that Northgate is signed, do some housekeeping on the Halyard material. The
                spreadsheet Declan gave us in particular. There is no reason for any of it to
                still be sitting in a mailbox.

                Do it before the audit team starts their fieldwork on the 20th.

                Dana""", List.of("SENSITIVE"));

        add(t6, "marcus", List.of("dana"), List.of(), t("2024-05-12T09:04:00Z"),
                "Re: Housekeeping",
                """
                Dana,

                I am not deleting anything. If the audit team asks me directly whether we saw
                Bridgeline's numbers I am going to tell them the truth, and I would rather the
                file existed when I did.

                I think we should go back to Owen.

                Marcus""", List.of("SENSITIVE"));

        add(t6, "dana", List.of("marcus"), List.of(), t("2024-05-12T09:26:00Z"),
                "Re: Housekeeping",
                """
                Do not put anything else about this in writing. I will call you.

                Dana""", List.of("SENSITIVE"));

        // --- Thread 7: the trigger for the investigation ------------------------------------
        String t7 = "halyard-audit-query";
        add(t7, "lena", List.of("owen"), List.of(), t("2024-06-03T08:55:00Z"),
                "PRIVILEGED — Northgate, query from Caldwell",
                """
                Owen,

                Caldwell have raised a query on the Northgate bid file. They have asked why the
                contingency line was zeroed between 21 February and submission, and they have
                asked for the working papers behind the 12.4 figure.

                I have not answered. I think we need to preserve the bid team's mailboxes before
                we go any further.

                Lena""", List.of("PRIVILEGED"));

        add(t7, "owen", List.of("lena"), List.of("holly"), t("2024-06-03T09:20:00Z"),
                "Re: PRIVILEGED — Northgate, query from Caldwell",
                """
                Agreed. Holly, please place a litigation hold on Dana Whitfield, Marcus Ellery
                and Priya Raghunathan effective immediately, covering all correspondence from
                1 January 2024 to date. Nothing in scope is to be deleted under the retention
                schedule until the hold is lifted.

                Lena, draft the preservation notice. I will brief Harold this afternoon.

                Owen""", List.of("PRIVILEGED"));

        // The recipient's captured copy of the smoking gun — same conversation, different
        // mailbox, different externalId. Near-duplicate detection and per-custodian scoping
        // both need this case to exist.
        Message danaCopy = messages.stream()
                .filter(m -> m.threadId().equals(Ids.threadId(t6)) && m.subject().equals("Housekeeping"))
                .findFirst().orElseThrow();
        messages.add(copyToMailbox(danaCopy, roster.key("marcus")));
    }

    /** Same message body, captured from a recipient's mailbox instead of the sender's. */
    private Message copyToMailbox(Message original, Custodian owner) {
        String externalId = nextExternalId();
        return new Message(
                Ids.messageId(externalId), externalId, "EXCHANGE", original.type(),
                owner.custodianId(), original.from(), original.to(), original.cc(),
                original.subject(), original.body(), original.sentAt(), original.threadId(),
                original.inReplyTo(), original.attachments(), original.labels());
    }

    private void add(String threadKey, String senderKey, List<String> toKeys, List<String> ccKeys,
                     Instant sentAt, String subject, String body, List<String> labels) {
        add(threadKey, senderKey, toKeys, ccKeys, sentAt, subject, body, labels, List.of());
    }

    private void add(String threadKey, String senderKey, List<String> toKeys, List<String> ccKeys,
                     Instant sentAt, String subject, String body, List<String> labels,
                     List<Attachment> attachmentSpecs) {
        Custodian sender = roster.key(senderKey);
        String externalId = nextExternalId();
        String threadId = Ids.threadId(threadKey);

        List<Attachment> attachments = new ArrayList<>();
        for (int i = 0; i < attachmentSpecs.size(); i++) {
            Attachment spec = attachmentSpecs.get(i);
            attachments.add(Attachments.of(externalId, i, spec.filename(),
                    java.util.Base64.getDecoder().decode(spec.contentBase64())));
        }

        Message m = new Message(
                Ids.messageId(externalId), externalId, "EXCHANGE", MessageType.EMAIL,
                sender.custodianId(), sender.email(),
                toKeys.stream().map(k -> roster.key(k).email()).toList(),
                ccKeys.stream().map(k -> roster.key(k).email()).toList(),
                subject, body.stripTrailing(), sentAt, threadId,
                lastInThread.get(threadKey), attachments, labels);
        messages.add(m);
        lastInThread.put(threadKey, m.messageId());
    }

    private String nextExternalId() {
        return String.format(Locale.ROOT, "EXCH-HALYARD-%04d", ++sequence);
    }

    /** Placeholder attachment carrying literal CSV rows; hashed properly in {@link #add}. */
    private static Attachment csv(String filename, String... rows) {
        byte[] bytes = (String.join("\n", Arrays.asList(rows)) + "\n").getBytes(StandardCharsets.UTF_8);
        return new Attachment(null, filename, "text/csv", bytes.length, null,
                java.util.Base64.getEncoder().encodeToString(bytes));
    }

    private static Instant t(String iso) {
        return Instant.parse(iso);
    }
}
