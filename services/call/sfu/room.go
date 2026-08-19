package sfu

import (
	"fmt"
	"io"
	"strings"
	"sync"

	"github.com/pion/webrtc/v4"
	"go.uber.org/zap"
)

type Participant struct {
	ID         string
	Name       string
	IsSpeaking bool
	IsMuted    bool
	IsCameraOff bool
	PubPC      *webrtc.PeerConnection
	SubPC      *webrtc.PeerConnection
}

type SFURoom struct {
	ID           string
	Name         string
	IsVideo      bool
	mu           sync.RWMutex
	participants map[string]*Participant
	tracks       map[string]*webrtc.TrackLocalStaticRTP
	log          *zap.Logger
	api          *webrtc.API
}

type SFURoomManager struct {
	mu    sync.RWMutex
	rooms map[string]*SFURoom
	log   *zap.Logger
	api   *webrtc.API
}

func NewSFURoomManager(log *zap.Logger) *SFURoomManager {
	// Initialize Pion WebRTC API with default media engine settings
	m := &webrtc.MediaEngine{}
	if err := m.RegisterDefaultCodecs(); err != nil {
		log.Error("failed to register default codecs", zap.Error(err))
	}
	api := webrtc.NewAPI(webrtc.WithMediaEngine(m))

	return &SFURoomManager{
		rooms: make(map[string]*SFURoom),
		log:   log,
		api:   api,
	}
}

func (m *SFURoomManager) GetOrCreateRoom(roomID, name string, isVideo bool) *SFURoom {
	m.mu.Lock()
	defer m.mu.Unlock()

	if r, exists := m.rooms[roomID]; exists {
		return r
	}

	r := &SFURoom{
		ID:           roomID,
		Name:         name,
		IsVideo:      isVideo,
		participants: make(map[string]*Participant),
		tracks:       make(map[string]*webrtc.TrackLocalStaticRTP),
		log:          m.log,
		api:          m.api,
	}
	m.rooms[roomID] = r
	m.log.Info("Created new SFU WebRTC room", zap.String("room_id", roomID), zap.String("name", name))
	return r
}

func (m *SFURoomManager) GetRoom(roomID string) (*SFURoom, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	r, exists := m.rooms[roomID]
	return r, exists
}

func (m *SFURoomManager) RemoveRoom(roomID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if r, exists := m.rooms[roomID]; exists {
		r.Close()
		delete(m.rooms, roomID)
		m.log.Info("Closed SFU WebRTC room", zap.String("room_id", roomID))
	}
}

// HandlePublisherOffer sets up the publisher PeerConnection and forwards media tracks to subscribers
func (r *SFURoom) HandlePublisherOffer(userID string, sdpOffer string) (string, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	config := webrtc.Configuration{
		ICEServers: []webrtc.ICEServer{
			{URLs: []string{"stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"}},
		},
	}

	pc, err := r.api.NewPeerConnection(config)
	if err != nil {
		return "", fmt.Errorf("failed to create publisher peer connection: %w", err)
	}

	p, exists := r.participants[userID]
	if !exists {
		p = &Participant{
			ID:   userID,
			Name: userID,
		}
		r.participants[userID] = p
	}
	if p.PubPC != nil {
		p.PubPC.Close()
	}
	p.PubPC = pc

	// Handle incoming media track from publisher
	pc.OnTrack(func(remoteTrack *webrtc.TrackRemote, receiver *webrtc.RTPReceiver) {
		r.log.Info("Received remote track on SFU",
			zap.String("room_id", r.ID),
			zap.String("user_id", userID),
			zap.String("kind", remoteTrack.Kind().String()),
		)

		// Create a local track to broadcast to subscribers
		trackID := fmt.Sprintf("%s-%s", userID, remoteTrack.Kind().String())
		localTrack, err := webrtc.NewTrackLocalStaticRTP(
			remoteTrack.Codec().RTPCodecCapability,
			remoteTrack.ID(),
			remoteTrack.StreamID(),
		)
		if err != nil {
			r.log.Error("Failed to create local static RTP track", zap.Error(err))
			return
		}

		r.mu.Lock()
		r.tracks[trackID] = localTrack
		// Attach new track to all existing subscribers in the room
		for subID, subP := range r.participants {
			if subID != userID && subP.SubPC != nil {
				if _, addErr := subP.SubPC.AddTrack(localTrack); addErr != nil {
					r.log.Warn("Failed to add track to subscriber", zap.String("sub_id", subID), zap.Error(addErr))
				}
			}
		}
		r.mu.Unlock()

		// Read RTP packets from remote publisher track and write to local broadcast track
		buf := make([]byte, 1500)
		for {
			n, _, readErr := remoteTrack.Read(buf)
			if readErr != nil {
				if readErr != io.EOF {
					r.log.Debug("RTP track read ended", zap.Error(readErr))
				}
				break
			}

			if _, writeErr := localTrack.Write(buf[:n]); writeErr != nil && writeErr != io.ErrClosedPipe {
				r.log.Warn("RTP track write error", zap.Error(writeErr))
			}
		}
	})

	// Set Remote SDP Offer
	offer := webrtc.SessionDescription{
		Type: webrtc.SDPTypeOffer,
		SDP:  sdpOffer,
	}
	if err := pc.SetRemoteDescription(offer); err != nil {
		return "", fmt.Errorf("failed to set remote description: %w", err)
	}

	// Generate SDP Answer
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		return "", fmt.Errorf("failed to create SDP answer: %w", err)
	}

	if err := pc.SetLocalDescription(answer); err != nil {
		return "", fmt.Errorf("failed to set local description: %w", err)
	}

	return answer.SDP, nil
}

// HandleSubscriberOffer sets up the subscriber PeerConnection to receive tracks from all publishers
func (r *SFURoom) HandleSubscriberOffer(userID string, sdpOffer string) (string, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	config := webrtc.Configuration{
		ICEServers: []webrtc.ICEServer{
			{URLs: []string{"stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"}},
		},
	}

	pc, err := r.api.NewPeerConnection(config)
	if err != nil {
		return "", fmt.Errorf("failed to create subscriber peer connection: %w", err)
	}

	p, exists := r.participants[userID]
	if !exists {
		p = &Participant{
			ID:   userID,
			Name: userID,
		}
		r.participants[userID] = p
	}
	if p.SubPC != nil {
		p.SubPC.Close()
	}
	p.SubPC = pc

	// Add all existing room tracks to subscriber PC
	prefix := fmt.Sprintf("%s-", userID)
	for trackOwnerID, t := range r.tracks {
		if !strings.HasPrefix(trackOwnerID, prefix) {
			if _, addErr := pc.AddTrack(t); addErr != nil {
				r.log.Warn("Failed to pre-add existing track to subscriber", zap.String("user_id", userID), zap.Error(addErr))
			}
		}
	}

	// Set Remote Offer
	offer := webrtc.SessionDescription{
		Type: webrtc.SDPTypeOffer,
		SDP:  sdpOffer,
	}
	if err := pc.SetRemoteDescription(offer); err != nil {
		return "", fmt.Errorf("failed to set subscriber remote description: %w", err)
	}

	// Create Answer
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		return "", fmt.Errorf("failed to create subscriber answer: %w", err)
	}

	if err := pc.SetLocalDescription(answer); err != nil {
		return "", fmt.Errorf("failed to set subscriber local description: %w", err)
	}

	return answer.SDP, nil
}

func (r *SFURoom) RemoveParticipant(userID string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if p, exists := r.participants[userID]; exists {
		if p.PubPC != nil {
			p.PubPC.Close()
		}
		if p.SubPC != nil {
			p.SubPC.Close()
		}
		delete(r.participants, userID)
	}
}

func (r *SFURoom) Close() {
	r.mu.Lock()
	defer r.mu.Unlock()

	for _, p := range r.participants {
		if p.PubPC != nil {
			p.PubPC.Close()
		}
		if p.SubPC != nil {
			p.SubPC.Close()
		}
	}
	r.participants = make(map[string]*Participant)
	r.tracks = make(map[string]*webrtc.TrackLocalStaticRTP)
}
