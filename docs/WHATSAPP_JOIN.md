# Joining ZZ Cash on WhatsApp (draft, 3 Nov 2026)

**Status:**
- **Step 1 is live:** the balance bot answers every customer, since 3 Nov 2026 18:45 IST.
- **The rest is a draft.** Each Odoo change runs only on the owner's yes, through `tools/odoo/whatsapp_join.py`,
  which dry-runs first.

## Why
- **Typing is the weak point.** Today the cashier types every new customer's number and name. A typo sends
  the bill and the ZZ Cash to someone else, and nothing records that the customer agreed to WhatsApp messages.
- **The rules in India:**
  - A mobile number can't be a condition of sale; a shop takes it only with the customer's express consent.
    This is the Department of Consumer Affairs advisory of 26 May 2023, repeated in Parliament in Feb 2026.
  - Commentary on the DPDP Act reads it as asking for clear, separate consent for WhatsApp messages.
  - The till already sells without a customer; that stays.
- **With a QR, the customer does it.** They send one WhatsApp message from their own phone. The number is
  right by construction, and their message is the consent.

## What Odoo already does
Checked on the live database, read-only, 3 Nov 2026.
- **A message from a new number makes a contact.** Odoo's WhatsApp app (account "ZenZebra") made 20 contacts
  this way:
  - **Kind:** all 20 are Store customers. Their creator is "Public user", who is not in the Sales team, so
    the Brand rule leaves them alone.
  - **Number:** stored as "+91XXXXXXXXXX".
  - **Name:** 16 of 20 are named with the number. "ZZ: fill first name" (action 1392) leaves their first name
    empty.
  - **Duplicates:** none has a twin on the same number.
  - **Bills:** none has bought yet.
- **A number Odoo knows keeps its contact.** 32 of the 52 chats are on contacts that existed before.
- **The balance bot is built** (automation 27, action 1391).
  - **What it answers:** "balance", "points", "zz cash", "cashback", "wallet", "kitna cash" and "kitne points".
  - **The reply:** the balance, how long it is valid and how much can be used.
  - **Who gets it:** one number only. `zz.balancebot.allowlist` has one entry, a test setting.
- **The café bot** (automation 17, action 1307) answers "menu" and "order".
- **Opting out isn't built.**
  - Contacts have `x_wa_optout`, but nothing sets it, and the WhatsApp bill automations (4, 9) don't check it.
  - `phone.blacklist` is empty.
- **The ₹30 welcome credit** comes with the first points earned (automation 7), not at sign-up.
- **Messages received so far:** 32 in all.

## The flow
1. **At the counter,** a standee says "Scan to get your bills and ZZ Cash on WhatsApp". The QR opens WhatsApp
   on ZenZebra's number with `JOIN ZENZEBRA` already typed. The customer taps Send.
2. **Odoo makes or finds their contact,** as today. The join bot then:
   - writes a note on the contact with the message and its date (the consent record);
   - clears `x_wa_optout` if it was set;
   - replies: welcome, and "tell the cashier your number ending 1234". If Odoo has no name, it adds "What's
     your first name?".

   **A member already** (a ZZ Cash card or a bill on that number) is told so instead.
3. **The customer replies with their name.** The name bot writes it on the contact, and "ZZ: fill first name"
   fills the first name. It only writes when:
   - the contact's name is still the number;
   - the bot asked within the last 30 minutes;
   - the reply is 2–40 letters and not a bot word ("balance", "stop", "menu" …).
4. **At the till,** the cashier types the last 4 digits (or the full number) and picks them: no name to type,
   no Save.
5. **After payment,** the WhatsApp bill and the ZZ Cash block arrive in the same chat about a minute later, as
   today.
6. **Any time:**
   - "balance" gets the balance bot's reply.
   - "STOP" makes the stop bot set `x_wa_optout` and confirm. The bill automations then skip that contact.
   - `JOIN ZENZEBRA` turns WhatsApp on again.

## Odoo changes, in order (each on the owner's yes)
A new script, `tools/odoo/whatsapp_join.py`. It dry-runs unless `--apply`, has `--rollback`, and refuses an
action that was edited by hand.
1. **Balance bot for everyone:** remove `zz.balancebot.allowlist`. This is the smallest step: the bot is
   already written and was in use on one test number.
   - **Done 3 Nov 2026 18:45 IST:** `whatsapp_join.py balance-bot --apply`.
   - **Before:** action 1391 matched the code reviewed that day (its SHA-256 is in the script). The test number
     had one reply, on 22 Oct, which was read.
   - **The old value** is saved in `.env.zz-backup.json` next to `.env` (git-ignored). `--rollback` puts it
     back.
   - **Known limit:** a number saved on two contacts (12 until they are merged) is told the total of both
     cards, while the till uses one card.
2. **STOP:**
   - a new server action and automation (on mail.message create, with the same filter as automation 27);
   - `('partner_id.x_wa_optout','=',False)` added to the filters of automations 4 and 9.

   Whether STOP also stops the bill itself is the owner's call: the draft stops every WhatsApp message.
3. **JOIN and the name reply:** two new server actions and automations with the same trigger. Each has its
   own allowlist (`zz.joinbot.allowlist`, `zz.namebot.allowlist`), so they answer test numbers first.
4. **The QR:**
   - **Link:** `https://wa.me/<ZenZebra's WhatsApp number, digits only>?text=JOIN%20ZENZEBRA`, printed at each
     counter.
   - **Store codes (optional):** `JOIN ZENZEBRA KLJ` records which store a member joined at.

The replies below are free text inside WhatsApp's 24-hour window, which opens when the customer writes. The
bots never write first, so they need no approved template. They post as OdooBot, like action 1391.

### Draft: JOIN (server action, Python code)
```python
ICP = env['ir.config_parameter'].sudo()
allow = [x.strip() for x in (ICP.get_param('zz.joinbot.allowlist', '') or '').split(',') if x.strip()]
root = env.ref('base.partner_root')
for msg in (records if not env.context.get('zz_bot') else []):
    ch = env['discuss.channel'].sudo().browse(msg.res_id)
    if msg.model != 'discuss.channel' or msg.message_type != 'whatsapp_message' or not ch.exists() or ch.channel_type != 'whatsapp' or not ch.whatsapp_partner_id or msg.author_id != ch.whatsapp_partner_id: continue
    num, words = (ch.whatsapp_number or '').strip().lstrip('+'), (msg.preview or '').lower().split()
    if not num or (allow and num not in allow) or words[:2] != ['join', 'zenzebra']: continue
    if env['mail.message'].sudo().search_count([('model', '=', 'discuss.channel'), ('res_id', '=', ch.id), ('author_id', '=', root.id), ('body', 'ilike', 'ZZ Cash'), ('create_date', '>=', datetime.datetime.now() - datetime.timedelta(minutes=2))]): continue  # no double reply
    p = ch.whatsapp_partner_id.sudo()
    store = (words[2] if len(words) > 2 else '').upper()[:10]
    p.message_post(body='Joined ZZ Cash on WhatsApp%s: sent "%s" from this number, agreeing to WhatsApp bills and ZZ Cash messages.' % ((' at ' + store) if store else '', (msg.preview or '')[:60]), subtype_xmlid='mail.mt_note', author_id=root.id)
    if p.x_wa_optout: p.write({'x_wa_optout': False})
    same = env['res.partner'].sudo().search([('phone_sanitized', '=', '+' + num)])
    member = env['loyalty.card'].sudo().search_count([('program_id', '=', 4), ('partner_id', 'in', same.ids)]) or env['pos.order'].sudo().search_count([('partner_id', 'in', same.ids)], limit=1)
    first = (p.x_first_name or '').strip()
    hi = ('Hey %s!' % first) if first else 'Hey!'
    if member: body = "%s You're already on ZZ Cash with this number. Send \"balance\" any time to see what you have 🦓" % hi
    else: body = "%s Welcome to ZZ Cash 🦓 At the counter, just tell the cashier your number ending %s. Your bill and cashback will land right here." % (hi, num[-4:])
    if not first: body += "\n\nWhat's your first name? Reply with just your name."
    ch.with_context(zz_bot=True).message_post(body=body, message_type='whatsapp_message', subtype_xmlid='mail.mt_comment', author_id=root.id)
```

### Draft: the name reply (server action, Python code)
```python
ICP = env['ir.config_parameter'].sudo()
allow = [x.strip() for x in (ICP.get_param('zz.namebot.allowlist', '') or '').split(',') if x.strip()]
root = env.ref('base.partner_root')
SKIP = ['balance', 'points', 'wallet', 'cashback', 'menu', 'order', 'cafe', 'stop', 'unsubscribe', 'join', 'yes', 'no', 'ok', 'okay', 'hi', 'hello', 'hey', 'thanks', 'thank you']
for msg in (records if not env.context.get('zz_bot') else []):
    ch = env['discuss.channel'].sudo().browse(msg.res_id)
    if msg.model != 'discuss.channel' or msg.message_type != 'whatsapp_message' or not ch.exists() or ch.channel_type != 'whatsapp' or not ch.whatsapp_partner_id or msg.author_id != ch.whatsapp_partner_id: continue
    num, p, text = (ch.whatsapp_number or '').strip().lstrip('+'), ch.whatsapp_partner_id.sudo(), ' '.join((msg.preview or '').split())
    if not num or (allow and num not in allow): continue
    if not (p.name or '').replace('+', '').replace(' ', '').isdigit(): continue  # the contact has a name already
    if not (2 <= len(text) <= 40 and text.replace(' ', '').replace('.', '').isalpha()) or text.lower() in SKIP: continue
    if not env['mail.message'].sudo().search_count([('model', '=', 'discuss.channel'), ('res_id', '=', ch.id), ('author_id', '=', root.id), ('body', 'ilike', 'first name?'), ('create_date', '>=', datetime.datetime.now() - datetime.timedelta(minutes=30))]): continue
    p.write({'name': text.title()})  # "ZZ: fill first name" (automation 28) fills x_first_name
    first = (p.x_first_name or '').strip()
    if first: ch.with_context(zz_bot=True).message_post(body="Thanks, %s! See you at the counter 🦓" % first, message_type='whatsapp_message', subtype_xmlid='mail.mt_comment', author_id=root.id)
```

### Draft: STOP (server action, Python code)
```python
root = env.ref('base.partner_root')
STOPS = ['stop', 'unsubscribe', 'stop messages', 'band karo', 'mat bhejo']
for msg in (records if not env.context.get('zz_bot') else []):
    ch = env['discuss.channel'].sudo().browse(msg.res_id)
    if msg.model != 'discuss.channel' or msg.message_type != 'whatsapp_message' or not ch.exists() or ch.channel_type != 'whatsapp' or not ch.whatsapp_partner_id or msg.author_id != ch.whatsapp_partner_id: continue
    num, text = (ch.whatsapp_number or '').strip().lstrip('+'), (msg.preview or '').strip().lower().strip('.! ')
    if not num or text not in STOPS: continue
    people = env['res.partner'].sudo().search([('phone_sanitized', '=', '+' + num)])
    people.write({'x_wa_optout': True})
    for p in people: p.message_post(body='Asked to stop WhatsApp messages ("%s").' % text, subtype_xmlid='mail.mt_note', author_id=root.id)
    ch.with_context(zz_bot=True).message_post(body="Done. We won't send WhatsApp messages to this number any more. Send JOIN ZENZEBRA to start again.", message_type='whatsapp_message', subtype_xmlid='mail.mt_comment', author_id=root.id)
```

## Till change (next build)
- **Found at once:**
  - **Today:** when typed digits match someone in the till's list, only the list is shown, and the list
    updates every 15 minutes (`CUSTOMER_DELTA_MS`). So a customer who joined a minute ago is missing, unless
    the cashier types the full number (0.10.0 asks Odoo then) or nobody in the list shares those digits.
  - **Change:** a digit search first pulls the list's changes when they are over 2 minutes old
    (`CustomerService.indexChanges`, one small call, at most once every 2 minutes), then searches again.
- **Number-only names:** a row whose name is just the number shows "name not given", so the cashier can ask.

## UAT (on device, after each step is live)
| # | Case | Expected |
|---|---|---|
| J1 | Scan the QR, send | Welcome reply within a minute, asking for the first name; in Odoo a Store customer with its +91 number and the "Joined ZZ Cash on WhatsApp" note |
| J2 | Reply with a name | "Thanks, Name!"; the contact's name and first name are set |
| J3 | Right after J1, type the last 4 digits at the till, sell | Found; the WhatsApp bill and ZZ Cash arrive in the same chat; the ₹30 welcome credit with the first points |
| J4 | An existing customer scans and sends | "You're already on ZZ Cash with this number" |
| J5 | Any customer sends "balance" | The balance bot's reply |
| J6 | "STOP", then a bill on that number, then `JOIN ZENZEBRA` | Confirmation; no WhatsApp bill; then back on |
| J7 | Asked for a name, reply "balance" | Name unchanged; the balance answered |

## Open
- **Meta's pricing:** check the current price for replies inside the 24-hour window. The bots only ever reply.
- **The WhatsApp profile name:** Odoo used it for 4 of the 20 new contacts. The draft asks for the name
  instead, so a profile name like "🌸" never becomes the contact's name.
- **The wording of the consent note and the standee:** for the owner, and a lawyer if wanted.
