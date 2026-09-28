import {
    HeaderNavigation,
    type HeaderNavigationProps,
    ALIGN,
    StyledNavigationList,
    StyledNavigationItem,
} from 'baseui/header-navigation';
import {styled, withStyle} from 'baseui';
import { StyledLink } from 'baseui/link';
import * as React from "react";
import ThemeToggle from "./ThemeToggle.tsx";
import GetStartedButton from "./GetStartedButton.tsx";

const TypedHeaderNavigation = (HeaderNavigation as unknown) as React.FC<React.PropsWithChildren<HeaderNavigationProps>>;

//brand typography and styling
const BrandText = styled('span', ({ $theme }) => ({
    ...$theme.typography.HeadingXXLarge,
    color: $theme.colors.contentPrimary,
    fontFamily: 'BrandFont, sans-serif',
    fontWeight: 700,
    cursor: 'pointer',
}));

//hover event styling for navigation buttons
const NavLink = withStyle(StyledLink, ({ $theme }) => ({
    ...$theme.typography.HeadingSmall,
    position: 'relative',
    textDecoration: 'none',
    color: $theme.colors.contentSecondary,
    cursor: 'pointer',
    transition: `color ${$theme.animation.timing300} ${$theme.animation.easeOutCurve}`,

    ':after': {
        content: '""',
        position: 'absolute',
        bottom: '0px',
        left: '5%',
        width: '90%',
        height: '2px',
        borderRadius: '1px',
        backgroundColor: $theme.colors.contentPrimary,
        transformOrigin: 'center',
        transform: 'scaleX(0) translateZ(0)',
        backfaceVisibility: 'hidden',
        transition: `transform ${$theme.animation.timing300} ${$theme.animation.easeOutCurve}`,
    },

    ':hover': {
        color: $theme.colors.contentPrimary,
        ':after': {
            transform: 'scaleX(1) translateZ(0)',
        },
    },
}));

interface NavbarProps {
    onNavigateHome?: () => void;
}

function Navbar({ onNavigateHome }: NavbarProps) {
    return (
        <TypedHeaderNavigation
            overrides={{
                Root: {
                    style: ({$theme}) => ({
                        borderRadius: '100px',
                        border: `1px solid ${$theme.colors.borderOpaque}`,
                        width: '100%',
                        boxSizing: 'border-box',
                        backgroundColor: $theme.colors.backgroundPrimary,
                        display: "flex",
                        flexDirection: 'row',
                        padding: `${$theme.sizing.scale200} ${$theme.sizing.scale600}`,
                        justifyContent: "space-between",
                        alignItems: "center",
                    }),
                }
            }}
        >
            {/* brand logo */}
            <StyledNavigationList $align={ALIGN.left}>
                <StyledNavigationItem>
                    <BrandText onClick={onNavigateHome}>Synapse</BrandText>
                </StyledNavigationItem>
            </StyledNavigationList>

            {/* navigation buttons - Chat UI removed as requested */}
            <StyledNavigationList $align={ALIGN.center}>
                <StyledNavigationItem>
                    <NavLink href={'#about'}>About</NavLink>
                </StyledNavigationItem>
                <StyledNavigationItem>
                    <NavLink href={"#features"}>Features</NavLink>
                </StyledNavigationItem>
                <StyledNavigationItem>
                    <NavLink href={"#pricing"}>Pricing</NavLink>
                </StyledNavigationItem>
            </StyledNavigationList>

            {/* action buttons */}
            <StyledNavigationList $align={ALIGN.right}>
                <StyledNavigationItem
                    style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: '8px',
                    }}>
                    <ThemeToggle/>
                    <GetStartedButton />
                </StyledNavigationItem>
            </StyledNavigationList>
        </TypedHeaderNavigation>
    );
}

export default Navbar;
