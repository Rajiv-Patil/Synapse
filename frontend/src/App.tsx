import { useState } from 'react'
import { styled } from 'baseui'
import { Button, SIZE, KIND } from 'baseui/button'
import { StatefulInput } from 'baseui/input'
import Navbar from './components/Navbar'
import './index.css';
//background wrapper with theme
const FullScreenLayout = styled('div', ({ $theme }) => ({
    height: '100%',
    width: '100%',
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    backgroundColor: $theme.colors.backgroundPrimary,
    boxSizing: 'border-box',
    transition: `background-color ${$theme.animation.timing300} ${$theme.animation.easeOutCurve}`
}))

//Center content
const Container = styled('div', {
    padding: '40px',
    width: '100%',
    maxWidth: '40%',
    display: 'flex',
    flexDirection: 'column',
    gap: '20px',
    border: "true",
    borderColor: "red",
    borderWidth: '1px',
    fontFamily: 'sans-serif',
})

//Dynamic tile
const Title = styled('h1', ({ $theme }) => ({
    fontSize: '24px',
    margin: 0,
    color: $theme.colors.contentPrimary,
}))

export default function App() {
    const [clickCount, setClickCount] = useState<number>(0)

    return (
        <FullScreenLayout>
            <Navbar />
            <Container>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <Title>Base Web Test</Title>
                </div>

                <StatefulInput
                    placeholder="Type something here..."
                    size={SIZE.default}
                />

                <Button
                    kind={KIND.primary}
                    size={SIZE.default}
                    onClick={() => setClickCount(prev => prev + 1)}
                >
                    Clicked {clickCount} times
                </Button>
            </Container>
        </FullScreenLayout>
    )
}
