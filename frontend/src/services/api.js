import axios from 'axios'

const API_BASE = '/api'

export const radioParadiseAPI = {
    async getRecordsPage(tab, params, signal) {
        const endpoint = tab === 'plays' ? 'plays' : 'tracks'
        const response = await axios.get(`${API_BASE}/records/${endpoint}`, { params, signal })
        return response.data
    },

    // Get all songs
    async getAllSongs() {
        const response = await axios.get(`${API_BASE}/songs`)
        return response.data
    },

    // Get songs by channel
    async getSongsByChannel(channelId) {
        const response = await axios.get(`${API_BASE}/songs/channel/${channelId}`)
        return response.data
    },

    // Get recent songs
    async getRecentSongs() {
        const response = await axios.get(`${API_BASE}/songs/recent`)
        return response.data
    },

    // Get most played songs
    async getMostPlayedSongs() {
        const response = await axios.get(`${API_BASE}/songs/most-played`)
        return response.data
    }
}

export const CHANNELS = {
    '0': { name: 'Main Mix', color: '#667eea' },
    '1': { name: 'Mellow Mix', color: '#4facfe' },
    '2': { name: 'Rock Mix', color: '#f093fb' }
}
