import { useState, useEffect } from 'react'
import { styled } from 'baseui'
import { Button, SIZE, KIND } from 'baseui/button'
import { ArrowRight } from 'baseui/icon'
import Navbar from './components/Navbar'
import ChatPage from './components/ChatPage'
import './index.css';

// Full height wrapper
const FullScreenLayout = styled('div', ({ $theme }) => ({
    minHeight: '100vh',
    width: '100%',
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    backgroundColor: $theme.colors.backgroundPrimary,
    boxSizing: 'border-box',
    transition: `background-color ${$theme.animation.timing300} ${$theme.animation.easeOutCurve}`
}))

// Center landing container
const LandingContainer = styled('div', {
    padding: '60px 24px',
    width: '100%',
    maxWidth: '860px',
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    textAlign: 'center',
    gap: '32px',
    boxSizing: 'border-box',
    marginTop: '20px',
})

const HeroBadge = styled('div', ({ $theme }) => ({
    display: 'inline-flex',
    alignItems: 'center',
    padding: `${$theme.sizing.scale200} ${$theme.sizing.scale500}`,
    borderRadius: $theme.borders.radius400,
    backgroundColor: $theme.colors.backgroundSecondary,
    border: `1px solid ${$theme.colors.borderOpaque}`,
    color: $theme.colors.contentSecondary,
    ...$theme.typography.LabelSmall,
    letterSpacing: '0.04em',
    textTransform: 'uppercase',
}))

const HeroTitle = styled('h1', ({ $theme }) => ({
    ...$theme.typography.HeadingXXLarge,
    fontSize: '56px',
    lineHeight: 1.12,
    margin: 0,
    color: $theme.colors.contentPrimary,
    fontWeight: 700,
    letterSpacing: '-1.5px',
}))

const FeatureGrid = styled('div', {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(250px, 1fr))',
    gap: '20px',
    width: '100%',
    marginTop: '24px',
})

const FeatureCard = styled('div', ({ $theme }) => ({
    backgroundColor: $theme.colors.backgroundSecondary,
    border: `1px solid ${$theme.colors.borderOpaque}`,
    borderRadius: $theme.borders.radius400,
    padding: '24px',
    textAlign: 'left',
    display: 'flex',
    flexDirection: 'column',
    gap: '10px',
    transition: `all ${$theme.animation.timing200} ${$theme.animation.easeOutCurve}`,
    ':hover': {
        borderColor: $theme.colors.borderSelected,
        transform: 'translateY(-2px)',
    }
}))

const FeatureTitle = styled('div', ({ $theme }) => ({
    ...$theme.typography.HeadingXSmall,
    color: $theme.colors.contentPrimary,
    fontWeight: 600,
}))

const FeatureDescription = styled('div', ({ $theme }) => ({
    ...$theme.typography.ParagraphSmall,
    color: $theme.colors.contentSecondary,
    lineHeight: 1.5,
}))

export default function App() {
    const [currentPath, setCurrentPath] = useState<string>(() => window.location.pathname)

    useEffect(() => {
        const handlePopState = () => {
            setCurrentPath(window.location.pathname)
        }
        window.addEventListener('popstate', handlePopState)
        return () => window.removeEventListener('popstate', handlePopState)
    }, [])

    const navigate = (path: string) => {
        window.history.pushState({}, '', path)
        setCurrentPath(path)
    }

    // Route to Chat Page if path is /chat or starts with /chat
    if (currentPath.startsWith('/chat')) {
        return <ChatPage onNavigateHome={() => navigate('/')} />
    }

    // Otherwise render Landing Page
    return (
        <FullScreenLayout>
            <div style={{ width: '100%', maxWidth: '1100px', padding: '20px 24px 0' }}>
                <Navbar onNavigateHome={() => navigate('/')} />
            </div>

            <LandingContainer>
                <HeroBadge>
                    Conversational Intelligence
                </HeroBadge>

                <HeroTitle style={{
                    margin: '10px'
                }}>
                    Transform Messy CSVs into Clean Intelligence
                </HeroTitle>


                <div style={{ display: 'flex', gap: '12px', flexWrap: 'wrap', justifyContent: 'center' }}>


                    <Button
                        size={SIZE.large}
                        kind={KIND.secondary}
                        onClick={() => { window.location.href = '/oauth2/authorization/cognito'; }}
                        overrides={{
                            BaseButton: {
                                style: ({ $theme }) => ({
                                    borderRadius: $theme.borders.radius400,
                                    paddingLeft: '28px',
                                    paddingRight: '28px',
                                })
                            }
                        }}
                    >
                        Sign in with Cognito
                    </Button>

                    <Button
                        size={SIZE.large}
                        kind={KIND.primary}
                        onClick={() => navigate('/chat')}
                        endEnhancer={<ArrowRight size={18} />}
                        overrides={{
                            BaseButton: {
                                style: ({ $theme }) => ({
                                    borderRadius: $theme.borders.radius400,
                                    paddingLeft: '28px',
                                    paddingRight: '28px',
                                })
                            }
                        }}
                    >
                        Try Studio
                    </Button>
                </div>

                <FeatureGrid>
                    <FeatureCard>
                        <FeatureTitle>Automated Health Checks</FeatureTitle>
                        <FeatureDescription>
                            Inspects header schema, encoding flags, structural row consistency, and per-column completeness.
                        </FeatureDescription>
                    </FeatureCard>

                    <FeatureCard>
                        <FeatureTitle>Autonomous Healing Engine</FeatureTitle>
                        <FeatureDescription>
                            Repairs underflow rows, trims overflows, labels unnamed columns, and standardizes delimiters.
                        </FeatureDescription>
                    </FeatureCard>

                    <FeatureCard>
                        <FeatureTitle>Conversational Data Studio</FeatureTitle>
                        <FeatureDescription>
                            Clean dual-panel workspace with persistent inquiry prompts, instant statistics, and schema inspection.
                        </FeatureDescription>
                    </FeatureCard>
                </FeatureGrid>
            </LandingContainer>
        </FullScreenLayout>
    )
}
