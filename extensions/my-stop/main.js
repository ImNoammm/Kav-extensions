// My stop: a Home card with the next departures from one stop.
//
// The rider types the stop's code in this extension's settings; Kav draws that page from the manifest and keeps the
// value. The card is made of Kav's own blocks: "stop" is Kav's row for a stop and "departures" its next timetabled
// departures, both from what is already on the phone, so this script asks nothing of the internet.

const words = (en, he) => (kav.locale === 'he' ? he : en);

kav.provide('slot.render', (input) => {
  if (input.slot !== 'home') return null;
  const code = String(input.settings.stop).trim();
  if (!/^\d{1,6}$/.test(code)) {
    return { blocks: [
      { type: 'notice', text: words('Type your stop\'s code in the settings to see its next departures here.',
        'הקלידו את קוד התחנה שלכם בהגדרות כדי לראות כאן את היציאות הבאות ממנה.') },
      { type: 'button', label: words('Open settings', 'פתיחת ההגדרות'), icon: 'settings', action: { settings: true } },
    ] };
  }
  const ref = `il.mot/gtfs/stop/${code}`;
  const blocks = [];
  if (input.settings.name) blocks.push({ type: 'stop', ref });
  blocks.push({ type: 'departures', ref, limit: input.settings.count });
  return { blocks };
});
