import { describe, expect, test } from 'vitest';
import { componentText, ENGLISH, translatedSettings, type ComponentTexts } from './componentTexts';
import { HTTP_FORMS, SFTP_FORMS } from './fake/forms';
import { endpointSettings } from './settings';

const czech = (bundles: Record<string, Record<string, string>>): ComponentTexts => ({ language: 'cs', bundles: new Map(Object.entries(bundles)) });

describe('componentText', () => {
  test("a key of the scheme shows the scheme's message, a key of the platform the platform's", () => {
    const texts = czech({ http: { 'http.field.url.label': 'Adresa URL' }, rail8: { 'rail8.tab.general': 'Obecné' } });
    expect(componentText(texts, 'http', 'http.field.url.label', 'URL')).toBe('Adresa URL');
    expect(componentText(texts, 'http', 'rail8.tab.general', 'General')).toBe('Obecné');
  });

  test('a key the bundle lacks, a scheme without a bundle and a text without a key each show the English', () => {
    const texts = czech({ http: { 'http.field.url.label': 'Adresa URL' } });
    expect(componentText(texts, 'http', 'http.field.url.help', 'Where to post.')).toBe('Where to post.');
    expect(componentText(texts, 'http', 'rail8.tab.general', 'General')).toBe('General');
    expect(componentText(texts, 'sftp', 'sftp.field.host.label', 'Host')).toBe('Host');
    expect(componentText(texts, 'http', undefined, 'Timeout')).toBe('Timeout');
    expect(componentText(ENGLISH, 'http', 'http.field.url.label', 'URL')).toBe('URL');
  });

  test("a key of another scheme is never read from this one's bundle", () => {
    const texts = czech({ http: { 'sftp.field.host.label': 'Hostitel', 'rail8.tab.general': 'Obecné' } });
    expect(componentText(texts, 'http', 'sftp.field.host.label', 'Host')).toBe('Host');
    expect(componentText(texts, 'http', 'rail8.tab.general', 'General')).toBe('General');
  });

  test('an adapter named like a module of the UI takes none of its texts', () => {
    // the UI's own flows.* texts are no table of components
    expect(componentText(czech({ flows: {} }), 'flows', 'flows.tab.steps', 'Steps')).toBe('Steps');
    expect(componentText(czech({}), 'endpoints', 'endpoints.field.replyLimit.help', 'How big a reply may be.')).toBe('How big a reply may be.');
  });

  test('a message is formatted as ICU, and one that does not format shows the English', () => {
    const texts = czech({
      http: {
        'http.field.a.label': "Partner'{'s'}' adresa",
        'http.field.b.label': 'Počet {count}',
        'http.field.c.label': 'Neuzavřená {',
        'http.field.d.label': '{n, plural, one {# soubor} other {# souborů}}',
      },
    });
    expect(componentText(texts, 'http', 'http.field.a.label', 'A')).toBe("Partner{s} adresa");
    expect(componentText(texts, 'http', 'http.field.b.label', 'Count')).toBe('Count');
    expect(componentText(texts, 'http', 'http.field.c.label', 'Open')).toBe('Open');
    expect(componentText(texts, 'http', 'http.field.d.label', 'Files')).toBe('Files');
  });

  test('a message holding markup shows it as typed', () => {
    const texts = czech({ http: { 'http.field.url.label': 'Adresa <b>URL</b>', 'http.field.url.help': '<a href="x">pozor</a> <br/>' } });
    expect(componentText(texts, 'http', 'http.field.url.label', 'URL')).toBe('Adresa <b>URL</b>');
    expect(componentText(texts, 'http', 'http.field.url.help', 'Help')).toBe('<a href="x">pozor</a> <br/>');
  });
});

describe('translatedSettings', () => {
  test("each tab, label and help of the description shows its translation, and the UI's own texts stay", () => {
    const settings = endpointSettings(HTTP_FORMS, 'out');
    const texts = czech({
      http: { 'http.field.url.label': 'Adresa URL', 'http.field.url.help': 'Kam se posílají požadavky.' },
      rail8: { 'rail8.tab.general': 'Obecné', 'rail8.tab.security': 'Zabezpečení' },
    });
    const shown = translatedSettings(settings, 'http', texts);
    expect(shown.tabs.map((t) => t.label)).toEqual(['Obecné', 'Retry', 'Recording', 'Zabezpečení']);
    const url = shown.fields.find((f) => f.name === 'url')!;
    expect([url.label, url.help]).toEqual(['Adresa URL', 'Kam se posílají požadavky.']);
    const timeout = shown.fields.find((f) => f.name === 'timeoutMs')!;
    expect([timeout.label, timeout.help]).toEqual(['Timeout (ms)', 'How long a request may take, in milliseconds.']);
    const reply = shown.fields.find((f) => f.name === 'maxBodyBytes')!;
    expect([reply.label, reply.help]).toEqual([{ id: 'endpoints.field.replyLimit' }, { id: 'endpoints.field.replyLimit.help' }]);
    expect(translatedSettings(settings, 'http', ENGLISH)).toEqual(settings);
  });

  test("each choice shows its translation, and one the bundle lacks its English", () => {
    const settings = endpointSettings(SFTP_FORMS, 'in');
    const shown = translatedSettings(settings, 'sftp', czech({ sftp: { 'sftp.field.afterSuccess.choice.move': 'Přesunout', 'sftp.tab.polling': 'Dotazování' } }));
    expect(shown.fields.find((f) => f.name === 'afterSuccess')!.choiceLabels).toEqual({ delete: 'Delete', move: 'Přesunout' });
    expect(shown.tabs.find((t) => t.id === 'polling')!.label).toBe('Dotazování');
  });
});
