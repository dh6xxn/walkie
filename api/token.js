const { AccessToken } = require('livekit-server-sdk');

module.exports = async (req, res) => {
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST required' });
  try {
    const { identity, room } = req.body || {};
    if (!identity || !room) return res.status(400).json({ error: 'identity and room are required' });
    if (!process.env.LIVEKIT_API_KEY || !process.env.LIVEKIT_API_SECRET) {
      return res.status(500).json({ error: 'LiveKit server credentials are not configured' });
    }
    const token = new AccessToken(process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET, { identity });
    token.addGrant({ roomJoin: true, room, canPublish: true, canSubscribe: true });
    return res.status(200).json({ token: await token.toJwt() });
  } catch (e) {
    console.error(e);
    return res.status(500).json({ error: 'Unable to issue token' });
  }
};
