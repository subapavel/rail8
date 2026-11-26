import { Alert } from 'antd';
import { useEffect, useState } from 'react';
import { instant, useText } from './language';
import { uiFetch } from './session';

/** A key pair whose leaf certificate ends within 30 days of the node's clock (`ending`), or has ended (`ended`), with its end. */
export interface EndingCertificate {
  name: string;
  notAfter: string;
  certificate: 'ending' | 'ended';
}

interface Listed {
  secrets?: { name: string; keyPair?: { notAfter?: string; certificate?: 'ending' | 'ended' } }[];
}

/**
 * The key pairs that the node's `GET /admin/secrets` marks as ending or ended, in the list's order, through the UI server's
 * `/admin` and `send` (the page's `uiFetch` by default); null when it gives no answer, as for a caller who may not list secrets.
 */
export async function readEndingCertificates(send: typeof fetch = uiFetch): Promise<EndingCertificate[] | null> {
  try {
    const res = await send('/admin/secrets', { headers: { accept: 'application/json' } });
    if (!res.ok) return null;
    const list = (await res.json()) as Listed;
    return (list.secrets ?? []).flatMap(({ name, keyPair }) =>
      keyPair?.certificate !== undefined && keyPair.notAfter !== undefined ? [{ name, notAfter: keyPair.notAfter, certificate: keyPair.certificate }] : [],
    );
  } catch {
    return null;
  }
}

/**
 * A warning that names each key pair whose leaf certificate ends within 30 days or has ended, with its end, and says to
 * enter a new certificate. It reads the list once, through `read`; it shows nothing when no certificate ends soon, and when
 * the caller may not list secrets or the node does not answer.
 */
export function CertificateAlert({ read = readEndingCertificates }: { read?: () => Promise<EndingCertificate[] | null> }) {
  const texts = useText();
  const { t } = texts;
  const [ending, setEnding] = useState<EndingCertificate[] | null>(null);
  useEffect(() => {
    let live = true;
    void read().then((answer) => {
      if (live) setEnding(answer);
    });
    return () => {
      live = false;
    };
  }, [read]);
  if (ending === null || ending.length === 0) return null;
  return (
    <Alert
      type="warning"
      showIcon
      title={t('frame.certificates', { count: ending.length })}
      description={
        <>
          <ul>
            {ending.map(({ name, notAfter, certificate }) => (
              <li key={name}>{t(certificate === 'ended' ? 'frame.certificates.ended' : 'frame.certificates.ending', { name, at: instant(texts, notAfter) })}</li>
            ))}
          </ul>
          {t('frame.certificates.description')}
        </>
      }
    />
  );
}
