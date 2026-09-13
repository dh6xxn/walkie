const { AccessToken } = require('livekit-server-sdk');

module.exports = async (req, res) => {
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST required' });
  try {
    const body = req.body || {};
    const identity = body.identity || body.participant_identity;
    const room = body.room || body.room_name;
    if (!identity || !room) return res.status(400).json({ error: 'identity and room are required' });

    const apiKey = process.env.LIVEKIT_API_KEY;
    const apiSecret = process.env.LIVEKIT_API_SECRET;
    const livekitUrl = process.env.LIVEKIT_URL;
    if (!apiKey || !apiSecret || !livekitUrl) return res.status(500).json({ error: 'LiveKit server credentials are not configured' });

    const token = new AccessToken(apiKey, apiSecret, { identity, ttl: '1h' });
    token.addGrant({ roomJoin: true, room, canPublish: true, canSubscribe: true, canPublishData: true });
    const participantToken = await token.toJwt();

    return res.status(200).json({ token: participantToken, participant_token: participantToken, server_url: livekitUrl });
  } catch (e) {
    console.error('Token generation failed:', e);
    return res.status(500).json({ error: 'Unable to issue token' });
  }
};
