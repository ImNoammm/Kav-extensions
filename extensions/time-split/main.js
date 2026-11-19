// Time split: a line under each search result that splits the trip's time into on board, waiting and walking.
//
// A results slot is asked once per search with every route, and answers one entry per route in the same order (null
// for nothing). A route's legs each have a kind ("ride", "wait", "walk", ...) and their times in seconds.

const words = (en, he) => (kav.locale === 'he' ? he : en);

function minutes(route, kind) {
  const seconds = route.legs.filter((leg) => leg.kind === kind).reduce((sum, leg) => sum + (leg.arr - leg.dep), 0);
  return Math.round(seconds / 60);
}

function line(route, settings) {
  const parts = [
    ['all', words('on board', 'בנסיעה'), minutes(route, 'ride')],
    ['wait', words('waiting', 'בהמתנה'), minutes(route, 'wait')],
    ['walk', words('walking', 'בהליכה'), minutes(route, 'walk')],
  ]
    .filter(([key]) => settings.show === 'all' || settings.show === key)
    .filter(([, , m]) => !(settings.quiet && m < 1))
    .map(([, label, m]) => words(`${m} min ${label}`, `${m} דק׳ ${label}`));
  return parts.length ? { blocks: [{ type: 'text', style: 'caption', text: parts.join(' · ') }] } : null;
}

kav.provide('slot.render', (input) => {
  if (input.slot !== 'results') return null;
  return { items: input.routes.map((route) => line(route, input.settings)) };
});
